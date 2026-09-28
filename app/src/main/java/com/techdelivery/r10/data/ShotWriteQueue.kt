package com.techdelivery.r10.data

import com.techdelivery.r10.protocol.shot.Shot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The single writer for the shot history. Every change to the file — a shot
 * arriving, a club tag, a delete — goes through here, in submission order
 * (DESIGN §8).
 *
 * This class used to be [ShotPersistSink]: a queue in front of `append` only, owned
 * by the foreground service. That left the UI calling the store directly, and two
 * writers on one file means the mutex *serialises* without *ordering*. Two failures
 * came out of that, and neither was the store's fault:
 *
 *  - a delete could be undone by an append still sitting in the queue;
 *  - a club pick on a shot that was not on disk yet matched nothing, was discarded,
 *    and was never retried.
 *
 * So the queue carries every kind of write, and the UI's writes go through the same
 * door. Both named failures are now impossible: the append for a shot is either
 * already ahead of the edit in the queue or is submitted before the shot is shown.
 *
 * Two doors, deliberately different:
 *
 *  - [submit] never blocks. A blocking shot collector would backpressure
 *    `R10Device.shots`, then `ProtocolEngine._events`, then the BLE inbound reader.
 *    When the queue is full the append is dropped, counted and surfaced — losing a
 *    shot is bad, stopping the device from delivering the next one is worse.
 *  - [apply] suspends until the writer has run the op and reports what happened. The
 *    user is already waiting, and the UI has to know whether the file changed before
 *    it mirrors the change into the live list or apologises for a refused write.
 *
 * [store] is injected rather than a concrete [ShotProtoStore] so tests can drive
 * success, refusal and failure without touching the filesystem.
 */
class ShotWriteQueue(
    private val store: ShotWriter,
    private val scope: CoroutineScope,
    private val capacity: Int = DEFAULT_CAPACITY,
    /**
     * Injectable so tests can drive the writer from a `TestScheduler`. Left as
     * [Dispatchers.IO] in production; a hardcoded dispatcher would make the writer
     * invisible to `advanceUntilIdle()` and untestable.
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Non-null while writes are failing; cleared as soon as one succeeds again. */
    val error = MutableStateFlow<String?>(null)

    /** Shots that never reached the queue because it was full or closed. */
    val droppedCount = MutableStateFlow(0)

    /**
     * User edits, in submission order. Separate from [queue] because appends must
     * never be able to wait behind an edit, and edits must never be dropped.
     */
    private val edits = Channel<Pair<ShotWriteOp, CompletableDeferred<WriteOutcome>>>(Channel.UNLIMITED)

    private val queue = Channel<Shot>(capacity)
    private var writerJob: Job? = null

    @Volatile
    private var closing = false

    /** True while the IO writer is alive. */
    val isRunning: Boolean get() = writerJob?.isActive == true

    /** Start the IO writer. Idempotent, so a reconnect cannot spawn a second one. */
    fun start() {
        if (writerJob?.isActive == true) return
        writerJob = scope.launch(ioDispatcher) {
            // Two loops, deliberately not one: an edit is not a shot and must not
            // occupy append capacity, and appends must not queue behind a user edit
            // that is waiting on the store. Ordering *within* each kind is FIFO,
            // and the store's own mutex orders the two against each other.
            launch {
                for ((op, reply) in edits) {
                    // A store call that throws must not take the writer with it. The
                    // reply has to be completed either way: left incomplete, whoever
                    // asked for the edit waits on it forever, and the next edit in the
                    // queue never runs.
                    val outcome = runCatching { runOp(op) }.getOrElse { failure ->
                        fail("write failed: ${failure.message}")
                        WriteOutcome.REJECTED
                    }
                    reply.complete(outcome)
                }
            }
            for (shot in queue) {
                val written = runCatching { store.append(shot) }
                when {
                    written.getOrDefault(false) -> clearError()
                    written.isFailure -> fail("shot ${shot.shotId}: ${written.exceptionOrNull()?.message}")
                    else -> Unit // dedup skip: nothing written, nothing wrong
                }
            }
        }
    }

    /**
     * Queue a shot for disk. Returns false when it could not be queued, which is the
     * only way a shot can fail to persist from here.
     */
    fun submit(shot: Shot): Boolean {
        if (queue.trySend(shot).isSuccess) return true
        droppedCount.value = droppedCount.value + 1
        fail(
            if (closing) {
                "queue closed (shutting down); shot ${shot.shotId} not saved"
            } else {
                "queue full ($capacity); shot ${shot.shotId} not saved"
            },
        )
        return false
    }

    /**
     * Run a user-initiated write and wait for its outcome.
     *
     * Suspends rather than dropping when the queue is busy: the user tapped, and a
     * write that silently never happens reads as a broken app. Returns
     * [WriteOutcome.REJECTED] when the queue is closed, so the caller can say so rather
     * than hang.
     *
     * The returned value is what the writer saw, so the UI mirrors into the live
     * list exactly when the file really changed.
     */
    suspend fun apply(op: ShotWriteOp): WriteOutcome {
        if (!isRunning) {
            // Nothing will ever run this. Saying so beats a caller waiting forever
            // on a writer that was never started.
            fail("writer is not running; that change was not saved")
            return WriteOutcome.REJECTED
        }
        val reply = CompletableDeferred<WriteOutcome>()
        if (edits.trySend(op to reply).isFailure) return WriteOutcome.REJECTED
        // The writer completes this on its own dispatcher; a failure there must not
        // take this caller's coroutine down with it.
        return runCatching { reply.await() }.getOrElse { failure ->
            fail("write failed: ${failure.message}")
            WriteOutcome.REJECTED
        }
    }

    /**
     * Close both queues and wait up to [timeoutMs] for the backlog to drain.
     * Returns true if the writer finished inside the window.
     *
     * Suspending rather than blocking on `runBlocking` so it is drivable under
     * `runTest`'s virtual clock.
     */
    suspend fun close(timeoutMs: Long): Boolean {
        closing = true
        edits.close()
        queue.close()
        // `join()` yields Unit, so a non-null result means it finished inside the
        // window and null means the timeout fired with rows still queued.
        val finished = runCatching { withTimeoutOrNull(timeoutMs) { writerJob?.join() } }
            .getOrNull()
        return finished != null
    }

    private suspend fun runOp(op: ShotWriteOp): WriteOutcome = when (op) {
        is ShotWriteOp.Append -> if (store.append(op.shot)) WriteOutcome.WRITTEN else WriteOutcome.NOT_FOUND
        is ShotWriteOp.SetClub -> store.updateClub(op.shotId, op.receivedAtMs, op.club)
        is ShotWriteOp.DeleteShot -> store.deleteShot(op.shotId, op.receivedAtMs)
    }.also { result ->
        // A refusal is not a disk failure and must not clear or set the error
        // banner: the store reports why, and the UI words it.
        if (result == WriteOutcome.WRITTEN) clearError()
    }

    private fun fail(message: String) {
        error.value = message
    }

    /**
     * A successful write clears a previous failure. Without this a one-off
     * transient error stays on screen until the next Start.
     */
    private fun clearError() {
        if (error.value != null) error.value = null
    }

    companion object {
        /** Backlog depth. Sized well above any single reconnect burst. */
        const val DEFAULT_CAPACITY = 512
    }
}

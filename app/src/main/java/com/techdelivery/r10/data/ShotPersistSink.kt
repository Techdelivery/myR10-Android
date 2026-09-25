package com.techdelivery.r10.data

import com.techdelivery.r10.protocol.shot.Shot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the handoff from live shots to disk: a bounded queue, one writer on the IO
 * dispatcher, and the failure signal the UI shows.
 *
 * Extracted from `R10ForegroundService` so the overflow and recovery behaviour is
 * unit-testable without Android. The service only wires the [error] flow into
 * `DeviceStateHolder.historyError`.
 *
 * Two invariants drive the design:
 *  - [submit] never blocks. A blocking shot collector would backpressure
 *    `R10Device.shots`, then `ProtocolEngine._events`, then the BLE inbound reader.
 *  - Disk latency lives on [Dispatchers.IO], off the delivery path entirely.
 *
 * [appender] is injected rather than a concrete [ShotCsvStore] so tests can drive
 * success and failure without touching the filesystem.
 */
class ShotPersistSink(
    private val appender: suspend (Shot) -> Boolean,
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
            for (shot in queue) {
                val written = runCatching { appender(shot) }
                when {
                    written.getOrDefault(false) -> clearError()
                    written.isFailure -> fail("shot ${shot.shotId}: ${written.exceptionOrNull()?.message}")
                    else -> Unit // dedup skip: nothing written, nothing wrong
                }
            }
        }
    }

    /**
     * Queue [shot] for disk. Returns false when it could not be queued, which is
     * the only way a shot can fail to persist from here.
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
     * Close the queue and wait up to [timeoutMs] for the backlog to drain.
     * Returns true if the writer finished inside the window.
     *
     * Suspending rather than blocking on `runBlocking` so it is drivable under
     * `runTest`'s virtual clock.
     */
    suspend fun close(timeoutMs: Long): Boolean {
        closing = true
        queue.close()
        // `join()` yields Unit, so a non-null result means it finished inside the
        // window and null means the timeout fired with rows still queued.
        val finished = runCatching { withTimeoutOrNull(timeoutMs) { writerJob?.join() } }
            .getOrNull()
        return finished != null
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

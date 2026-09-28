package com.techdelivery.r10.data

import LaunchMonitor.Proto.R10Protos
import com.techdelivery.r10.club.GolfClub
import com.techdelivery.r10.protocol.shot.Shot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * M3 — the shot -> disk handoff — and DESIGN §8's ordering contract on top of it.
 *
 * The ordering tests at the bottom are the reason this class exists in its current
 * shape. When the sink fronted `append` only and the UI called the store directly,
 * two failures had no test because there was nowhere to put one: a delete could be
 * undone by an append still sitting in the queue, and a club pick on a shot that was
 * not on disk yet matched nothing.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ShotWriteQueueTest {

    private fun shot(id: Int) = Shot(
        shotId = id,
        shotType = R10Protos.Metrics.ShotType.NORMAL,
        receivedAtMs = 1_000L + id,
    )

    /** A writer that records the order it was called in and can be told to stall. */
    private class FakeWriter : ShotWriter {
        val calls = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        var stall = false
        var appendOutcome: WriteOutcome = WriteOutcome.WRITTEN
        var clubOutcome: WriteOutcome = WriteOutcome.WRITTEN
        var deleteOutcome: WriteOutcome = WriteOutcome.WRITTEN

        override suspend fun append(shot: Shot): Boolean {
            calls.add("append ${shot.shotId}")
            if (stall) gate.await()
            return true
        }

        override suspend fun updateClub(shotId: Int, receivedAtMs: Long, club: GolfClub?): WriteOutcome {
            calls.add("club $shotId=${club?.id}")
            if (stall) gate.await()
            return clubOutcome
        }

        override suspend fun deleteShot(shotId: Int, receivedAtMs: Long): WriteOutcome {
            calls.add("delete $shotId")
            if (stall) gate.await()
            return deleteOutcome
        }
    }

    @Test
    fun submittedShotsReachTheAppender() = runTest {
        val written = mutableListOf<Int>()
        val queue = ShotWriteQueue(
            object : ShotWriter {
                override suspend fun append(shot: Shot) = written.add(shot.shotId).let { true }
                override suspend fun updateClub(shotId: Int, receivedAtMs: Long, club: GolfClub?) = WriteOutcome.WRITTEN
                override suspend fun deleteShot(shotId: Int, receivedAtMs: Long) = WriteOutcome.WRITTEN
            },
            backgroundScope,
            8,
            UnconfinedTestDispatcher(testScheduler),
        )
        queue.start()
        repeat(5) { assertTrue(queue.submit(shot(it))) }

        assertTrue("writer should drain inside the window", queue.close(5_000))
        assertEquals((0..4).toList(), written.sorted())
        assertNull(queue.error.value)
    }

    @Test
    fun appenderFailureSurfacesAndALaterSuccessClearsIt() = runTest {
        var failing = true
        val queue = ShotWriteQueue(
            object : ShotWriter {
                override suspend fun append(shot: Shot): Boolean {
                    if (failing) throw IOException("disk gone")
                    return true
                }
                override suspend fun updateClub(shotId: Int, receivedAtMs: Long, club: GolfClub?) = WriteOutcome.WRITTEN
                override suspend fun deleteShot(shotId: Int, receivedAtMs: Long) = WriteOutcome.WRITTEN
            },
            backgroundScope,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        queue.start()
        queue.submit(shot(1))
        testScheduler.advanceUntilIdle()

        val err = queue.error.value
        assertNotNull("a throwing append must surface", err)
        assertTrue(err!!.contains("disk gone"))
        assertTrue(err.contains("shot 1"))

        failing = false
        queue.submit(shot(2))
        testScheduler.advanceUntilIdle()
        assertNull("a successful append must clear the stale error", queue.error.value)
        queue.close(5_000)
    }

    /**
     * The writer is deliberately left unstarted so nothing drains the queue and the
     * bounded-capacity path is forced.
     */
    @Test
    fun fullQueueDropsAndCounts() = runTest {
        val queue = ShotWriteQueue(
            object : ShotWriter {
                override suspend fun append(shot: Shot) = true
                override suspend fun updateClub(shotId: Int, receivedAtMs: Long, club: GolfClub?) = WriteOutcome.WRITTEN
                override suspend fun deleteShot(shotId: Int, receivedAtMs: Long) = WriteOutcome.WRITTEN
            },
            backgroundScope,
            2,
            UnconfinedTestDispatcher(testScheduler),
        )
        assertTrue(queue.submit(shot(1)))
        assertTrue(queue.submit(shot(2)))
        assertFalse("third submit must not block and must report failure", queue.submit(shot(3)))

        assertEquals(1, queue.droppedCount.value)
        assertNotNull(queue.error.value)
        assertTrue(queue.error.value!!.contains("full"))
    }

    @Test
    fun closeReportsFalseWhenTheWriterCannotDrainInTime() = runTest {
        val writer = FakeWriter().apply { stall = true }
        val queue = ShotWriteQueue(writer, backgroundScope, 4, UnconfinedTestDispatcher(testScheduler))
        queue.start()
        queue.submit(shot(1))
        testScheduler.advanceUntilIdle()

        assertFalse("writer is wedged, so the drain must time out", queue.close(1_000))
        writer.gate.complete(Unit)
    }

    @Test
    fun startIsIdempotentSoAReconnectCannotSpawnASecondWriter() = runTest {
        val writer = FakeWriter()
        val queue = ShotWriteQueue(writer, backgroundScope, ioDispatcher = UnconfinedTestDispatcher(testScheduler))
        queue.start()
        queue.start()
        queue.start()
        queue.submit(shot(1))
        testScheduler.advanceUntilIdle()

        assertEquals("exactly one writer may consume the queue", 1, writer.calls.size)
        queue.close(1_000)
    }

    @Test
    fun submitAfterCloseIsCountedAsDropped() = runTest {
        val queue = ShotWriteQueue(
            FakeWriter(),
            backgroundScope,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        queue.start()
        assertTrue(queue.close(1_000))

        assertFalse(queue.submit(shot(9)))
        assertEquals(1, queue.droppedCount.value)
        assertTrue(queue.error.value!!.contains("closed"))
    }

    // --- DESIGN §8: who owns ordering between the UI and the persist queue ---

    /**
     * The failure this whole change exists for. A delete submitted while the shot's
     * own append is still queued used to be undone by that append landing after it.
     */
    @Test
    fun aDeleteSubmittedBehindAQueuedAppendStaysDeleted() = runTest {
        val writer = FakeWriter().apply { stall = true }
        val queue = ShotWriteQueue(writer, backgroundScope, 8, UnconfinedTestDispatcher(testScheduler))
        queue.start()

        queue.submit(shot(1)) // queued, not yet written: the writer is stalled
        // Launched rather than awaited: the UI does not block the main thread while
        // the write runs, and the test cannot release the writer from inside a call
        // that is waiting for it.
        val edited = async { queue.apply(ShotWriteOp.DeleteShot(1, shot(1).receivedAtMs)) }
        testScheduler.advanceUntilIdle()
        writer.gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(WriteOutcome.WRITTEN, edited.await())
        // The append ran first, then the delete: the shot is not left behind.
        assertEquals(listOf("append 1", "delete 1"), writer.calls)
    }

    /**
     * The second named failure: a club pick on a shot that is not on disk yet used to
     * match nothing, be discarded, and never be retried.
     */
    @Test
    fun aClubPickBehindAQueuedAppendLandsOnTheShotItWasAimedAt() = runTest {
        val writer = FakeWriter().apply { stall = true }
        val queue = ShotWriteQueue(writer, backgroundScope, 8, UnconfinedTestDispatcher(testScheduler))
        queue.start()

        queue.submit(shot(1))
        val edited = async { queue.apply(ShotWriteOp.SetClub(1, shot(1).receivedAtMs, GolfClub.DRIVER)) }
        testScheduler.advanceUntilIdle()
        writer.gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(WriteOutcome.WRITTEN, edited.await())
        assertEquals(listOf("append 1", "club 1=${GolfClub.DRIVER.id}"), writer.calls)
    }

    /** Two edits, submitted in order, run in order — even with the writer stalled. */
    @Test
    fun editsRunInSubmissionOrder() = runTest {
        val writer = FakeWriter().apply { stall = true }
        val queue = ShotWriteQueue(writer, backgroundScope, ioDispatcher = UnconfinedTestDispatcher(testScheduler))
        queue.start()

        val first = async { queue.apply(ShotWriteOp.DeleteShot(1, 1_001L)) }
        val second = async { queue.apply(ShotWriteOp.SetClub(2, 1_002L, GolfClub.PUTTER)) }
        testScheduler.advanceUntilIdle()
        writer.gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(WriteOutcome.WRITTEN, first.await())
        assertEquals(WriteOutcome.WRITTEN, second.await())
        assertEquals(listOf("delete 1", "club 2=${GolfClub.PUTTER.id}"), writer.calls)
    }

    /**
     * The store's Boolean could not tell these apart, so the UI re-read and
     * re-validated the whole file to find out which one it was.
     */
    @Test
    fun eachRefusalIsReportedAsItself() = runTest {
        val writer = FakeWriter()
        val queue = ShotWriteQueue(writer, backgroundScope, ioDispatcher = UnconfinedTestDispatcher(testScheduler))
        queue.start()

        writer.clubOutcome = WriteOutcome.NOT_FOUND
        assertEquals(WriteOutcome.NOT_FOUND, queue.apply(ShotWriteOp.SetClub(9, 9_009L, GolfClub.DRIVER)))
        writer.deleteOutcome = WriteOutcome.DAMAGED
        assertEquals(WriteOutcome.DAMAGED, queue.apply(ShotWriteOp.DeleteShot(9, 9_009L)))

        queue.close(1_000)
    }

    /**
     * A refusal is not a disk failure: it must not trip the error banner, because
     * the UI words it itself and the banner is a different claim.
     */
    @Test
    fun aRefusalDoesNotRaiseTheDiskErrorBanner() = runTest {
        val writer = FakeWriter().apply { clubOutcome = WriteOutcome.DAMAGED }
        val queue = ShotWriteQueue(writer, backgroundScope, ioDispatcher = UnconfinedTestDispatcher(testScheduler))
        queue.start()

        queue.apply(ShotWriteOp.SetClub(1, 1_001L, GolfClub.DRIVER))
        testScheduler.advanceUntilIdle()

        assertNull(queue.error.value)
        queue.close(1_000)
    }

    /**
     * Appends drop when the queue is full because they must never block the BLE path.
     * An edit the user tapped must not be treated the same way.
     */
    @Test
    fun anEditIsNotRejectedByAFullAppendQueue() = runTest {
        val writer = FakeWriter().apply { stall = true }
        val queue = ShotWriteQueue(writer, backgroundScope, 2, UnconfinedTestDispatcher(testScheduler))
        queue.start()
        repeat(4) { queue.submit(shot(it)) }
        assertFalse("append queue is full", queue.submit(shot(9)))

        val edit = async { queue.apply(ShotWriteOp.SetClub(1, 1_001L, GolfClub.DRIVER)) }
        testScheduler.advanceUntilIdle()
        writer.gate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(WriteOutcome.WRITTEN, edit.await())
        assertTrue("the appends that did not fit were dropped", queue.droppedCount.value > 0)
    }

    @Test
    fun applyOnAQueueWithNoWriterIsRejectedRatherThanHangingForever() = runTest {
        val queue = ShotWriteQueue(FakeWriter(), backgroundScope)
        assertEquals(WriteOutcome.REJECTED, queue.apply(ShotWriteOp.DeleteShot(1, 1_001L)))
        assertTrue(queue.error.value!!.contains("not running"))
    }
}

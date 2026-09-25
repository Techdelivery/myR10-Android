package com.techdelivery.r10.data

import LaunchMonitor.Proto.R10Protos
import com.techdelivery.r10.protocol.shot.Shot
import kotlinx.coroutines.CompletableDeferred
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
 * M3 — the shot -> disk handoff. These are the cases the foreground service used to
 * own inline and could not be tested for, having no Robolectric here.
 */
class ShotPersistSinkTest {

    private fun shot(id: Int) = Shot(
        shotId = id,
        shotType = R10Protos.Metrics.ShotType.NORMAL,
        receivedAtMs = 1_000L + id,
    )

    @Test
    fun submittedShotsReachTheAppender() = runTest {
        val written = mutableListOf<Int>()
        val sink =
            ShotPersistSink({ s ->
                written.add(s.shotId)
                true
            }, backgroundScope, 8, UnconfinedTestDispatcher(testScheduler))
        sink.start()
        repeat(5) { assertTrue(sink.submit(shot(it))) }

        assertTrue("writer should drain inside the window", sink.close(5_000))
        assertEquals((0..4).toList(), written.sorted())
        assertNull(sink.error.value)
    }

    @Test
    fun appenderFailureSurfacesAndALaterSuccessClearsIt() = runTest {
        var failing = true
        val sink = ShotPersistSink(
            { _ -> if (failing) throw IOException("disk gone") else true },
            backgroundScope,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        sink.start()
        sink.submit(shot(1))
        testScheduler.advanceUntilIdle()

        val err = sink.error.value
        assertNotNull("a throwing append must surface", err)
        assertTrue(err!!.contains("disk gone"))
        assertTrue(err.contains("shot 1"))

        failing = false
        sink.submit(shot(2))
        testScheduler.advanceUntilIdle()
        assertNull("a successful append must clear the stale error", sink.error.value)
        sink.close(5_000)
    }

    /**
     * The writer is deliberately left unstarted so nothing drains the queue and the
     * bounded-capacity path is forced.
     */
    @Test
    fun fullQueueDropsAndCounts() = runTest {
        val sink = ShotPersistSink({ true }, backgroundScope, 2, UnconfinedTestDispatcher(testScheduler))
        assertTrue(sink.submit(shot(1)))
        assertTrue(sink.submit(shot(2)))
        assertFalse("third submit must not block and must report failure", sink.submit(shot(3)))

        assertEquals(1, sink.droppedCount.value)
        assertNotNull(sink.error.value)
        assertTrue(sink.error.value!!.contains("full"))
    }

    @Test
    fun closeReportsFalseWhenTheWriterCannotDrainInTime() = runTest {
        val gate = CompletableDeferred<Unit>()
        val sink =
            ShotPersistSink({ _ ->
                gate.await()
                true
            }, backgroundScope, 4, UnconfinedTestDispatcher(testScheduler))
        sink.start()
        sink.submit(shot(1))
        testScheduler.advanceUntilIdle()

        assertFalse("writer is wedged, so the drain must time out", sink.close(1_000))
        gate.complete(Unit)
    }

    @Test
    fun startIsIdempotentSoAReconnectCannotSpawnASecondWriter() = runTest {
        var writes = 0
        val sink =
            ShotPersistSink({ _ ->
                writes++
                true
            }, backgroundScope, ioDispatcher = UnconfinedTestDispatcher(testScheduler))
        sink.start()
        sink.start()
        sink.start()
        sink.submit(shot(1))
        testScheduler.advanceUntilIdle()

        assertEquals("exactly one writer may consume the queue", 1, writes)
        sink.close(1_000)
    }

    @Test
    fun submitAfterCloseIsCountedAsDropped() = runTest {
        val sink = ShotPersistSink({ true }, backgroundScope, ioDispatcher = UnconfinedTestDispatcher(testScheduler))
        sink.start()
        assertTrue(sink.close(1_000))

        assertFalse(sink.submit(shot(9)))
        assertEquals(1, sink.droppedCount.value)
        assertTrue(sink.error.value!!.contains("closed"))
    }
}

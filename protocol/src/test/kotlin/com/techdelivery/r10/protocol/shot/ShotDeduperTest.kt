package com.techdelivery.r10.protocol.shot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * M2 — DESIGN §7.2: shots are deduplicated by the device's `shot_id`.
 */
class ShotDeduperTest {

    @Test
    fun firstIdAcceptedDuplicateRejected() {
        val d = ShotDeduper()
        assertTrue(d.accept(1))
        assertFalse(d.accept(1))
        assertTrue(d.accept(2))
        assertEquals(2, d.seenCount)
        assertTrue(d.isSeen(1))
        assertFalse(d.isSeen(99))
    }

    @Test
    fun resetClearsSeenIds() {
        val d = ShotDeduper()
        d.accept(7)
        d.reset()
        assertEquals(0, d.seenCount)
        assertTrue("after reset the same id must be acceptable again", d.accept(7))
    }

    @Test
    fun concurrentAcceptOnlyWinsOnce() {
        val d = ShotDeduper()
        val threads = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        val accepted = java.util.concurrent.atomic.AtomicInteger(0)
        val jobs = (1..threads).map {
            pool.submit {
                start.await()
                if (d.accept(1234)) accepted.incrementAndGet()
            }
        }
        start.countDown()
        jobs.forEach { it.get() }
        pool.shutdown()
        assertEquals("exactly one thread may accept a given shot_id", 1, accepted.get())
        assertEquals(1, d.seenCount)
    }
}

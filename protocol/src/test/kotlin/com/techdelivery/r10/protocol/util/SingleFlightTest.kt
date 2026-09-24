package com.techdelivery.r10.protocol.util

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

/**
 * G1 — write-queue serialization: concurrent submissions must never overlap.
 */
class SingleFlightTest {

    @Test
    fun concurrentRunsNeverOverlap() = runTest {
        val sf = SingleFlight()
        val active = AtomicInteger(0)
        var maxActive = 0
        val completed = AtomicInteger(0)

        val jobs = (1..20).map {
            async {
                sf.run {
                    val now = active.incrementAndGet()
                    if (now > maxActive) maxActive = now
                    delay(5) // hold the slot
                    active.decrementAndGet()
                    completed.incrementAndGet()
                }
            }
        }
        jobs.awaitAll()

        assertEquals(20, completed.get())
        assertEquals("max concurrent operations must be 1", 1, maxActive)
    }

    @Test
    fun returnsBlockResult() = runTest {
        val sf = SingleFlight()
        assertEquals(42, sf.run { 42 })
    }
}

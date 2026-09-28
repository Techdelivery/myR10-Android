package com.techdelivery.r10.data

import com.techdelivery.r10.protocol.shot.Shot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bounded LRU behind cross-session duplicate rejection (DESIGN §8).
 *
 * This unit had no test at all since it was split out of the store — TODO.md L8
 * records the same gap, and ROADMAP parked item E names it. It went untested
 * because the store's own tests only ever exercised the index with a window of
 * 2000 and a handful of shots, so every path through the eviction logic — which
 * is the whole point of the class — was unreachable from a test.
 *
 * The window is a constructor parameter precisely so eviction can be driven in a
 * few lines. These tests use a window of 2 or 3 to make every eviction happen.
 */
class ShotDedupIndexTest {

    private fun index(window: Int) = ShotDedupIndex(window)

    private fun shot(id: Int, payload: String = "p$id") = Shot(
        shotId = id,
        shotType = LaunchMonitor.Proto.R10Protos.Metrics.ShotType.NORMAL,
        receivedAtMs = 1_000L + id,
        rawMetrics = payload.toByteArray(),
    )

    private fun keyOf(shot: Shot): String? = shot.rawMetrics.takeIf { it.isNotEmpty() }?.decodeToString()

    @Test
    fun aFreshIndexKnowsNothing() {
        assertFalse(index(4).contains("a"))
    }

    @Test
    fun aKeyIsKnownOnceAdded() {
        val i = index(4)
        i.add("a")
        assertTrue(i.contains("a"))
        assertFalse("a neighbour must not be swept in", i.contains("b"))
    }

    @Test
    fun addingTheSameKeyTwiceKeepsOneEntry() {
        val i = index(1)
        i.add("a")
        i.add("a")
        // With a window of 1 a duplicate add must not evict the key it re-added.
        assertTrue(i.contains("a"))
    }

    /** The named verify for parked item E: the oldest key goes, the rest stay. */
    @Test
    fun theLeastRecentlySeenKeyIsEvicted() {
        val i = index(2)
        i.add("a")
        i.add("b")
        i.add("c")

        assertFalse("a is the least recently seen", i.contains("a"))
        assertTrue(i.contains("b"))
        assertTrue(i.contains("c"))
    }

    /**
     * What makes it an LRU rather than a FIFO ring, and the reason `contains` is not
     * a pure read: a key the device keeps replaying must not be evicted while
     * long-dead ones survive. Drop the touch in `contains` and this fails.
     */
    @Test
    fun observingAKeyMakesItRecentSoItIsNotTheNextToGo() {
        val i = index(2)
        i.add("a")
        i.add("b")

        assertTrue(i.contains("a")) // "a" is now the most recent, "b" the least
        i.add("c")

        assertTrue("a was re-observed, so b is the one that should go", i.contains("a"))
        assertFalse(i.contains("b"))
        assertTrue(i.contains("c"))
    }

    @Test
    fun theIndexNeverGrowsPastItsWindow() {
        val i = index(5)
        repeat(500) { i.add("k$it") }

        // Only the newest five are remembered.
        repeat(495) { assertFalse("k$it must have been evicted", i.contains("k$it")) }
        (495..499).forEach { assertTrue(i.contains("k$it")) }
    }

    @Test
    fun clearForgetsEverything() {
        val i = index(4)
        i.add("a")
        i.clear()
        assertFalse(i.contains("a"))

        i.add("b")
        assertTrue(i.contains("b"))
        assertFalse("a clear must leave no ghost behind", i.contains("a"))
    }

    @Test
    fun seedFromRebuildsFromTheShotsOnDisk() {
        val i = index(4)
        i.add("stale")
        i.seedFrom(listOf(shot(1), shot(2)), ::keyOf)

        assertFalse("a reseed must not keep what came before it", i.contains("stale"))
        assertTrue(i.contains("p1"))
        assertTrue(i.contains("p2"))
    }

    /**
     * A shot with no raw payload carries no key and cannot be deduped — the store
     * writes those unconditionally, and the index must not invent a key for them.
     */
    @Test
    fun seedFromSkipsAShotWithNoPayload() {
        val i = index(4)
        i.seedFrom(listOf(Shot(1, LaunchMonitor.Proto.R10Protos.Metrics.ShotType.NORMAL, 1L)), ::keyOf)
        assertFalse("nothing keyable was seeded, so nothing should be known", i.contains(""))
    }

    @Test
    fun seedFromKeepsTheNewestWhenThereAreMoreThanTheWindow() {
        val i = index(2)
        i.seedFrom(listOf(shot(1), shot(2), shot(3), shot(4)), ::keyOf)

        // The list is oldest-first, as the store hands it over.
        assertFalse(i.contains("p1"))
        assertFalse(i.contains("p2"))
        assertTrue(i.contains("p3"))
        assertTrue(i.contains("p4"))
        assertEquals(2, countKnown(i))
    }

    /** Private state has no accessor, so "how many are remembered" is asked this way. */
    private fun countKnown(i: ShotDedupIndex): Int = (0..8).count { i.contains("p$it") }
}

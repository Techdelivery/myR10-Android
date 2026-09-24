package com.techdelivery.r10.protocol.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HexLogTest {

    @Test
    fun appendAndSnapshot_orderOldestToNewest() {
        val log = HexLog(capacity = 10)
        log.tx(byteArrayOf(1, 2), timestampMs = 100)
        log.rx(byteArrayOf(3), timestampMs = 200)
        val snap = log.snapshot()
        assertEquals(2, snap.size)
        assertEquals(HexDirection.TX, snap[0].direction)
        assertEquals(HexDirection.RX, snap[1].direction)
        assertEquals(100L, snap[0].timestampMs)
    }

    @Test
    fun wrapAround_evictsOldestKeepingCapacity() {
        val log = HexLog(capacity = 3)
        for (i in 1..5) log.tx(byteArrayOf(i.toByte()), timestampMs = i.toLong())
        assertEquals(3, log.size())
        val snap = log.snapshot()
        // last three entries: 3,4,5
        assertEquals(listOf(3, 4, 5), snap.map { it.bytes[0].toInt() })
    }

    @Test
    fun snapshot_isNonDestructive() {
        val log = HexLog(capacity = 5)
        log.tx(byteArrayOf(9))
        val a = log.snapshot()
        val b = log.snapshot()
        assertEquals(a, b)
        assertEquals(1, log.size())
    }

    @Test
    fun concurrentAppends_areSafeAndCounted() {
        val log = HexLog(capacity = 4096)
        val t1 = Thread { repeat(500) { log.tx(byteArrayOf(1)) } }
        val t2 = Thread { repeat(500) { log.rx(byteArrayOf(2)) } }
        t1.start(); t2.start(); t1.join(); t2.join()
        assertEquals(1000, log.size())
    }

    @Test
    fun exportHex_goldenLineFormat() {
        val log = HexLog(capacity = 10)
        log.tx(byteArrayOf(0xB4.toByte(), 0x13))
        log.rx(byteArrayOf(0x88.toByte(), 0x13))
        val lines = log.exportHex().split("\n")
        assertEquals("TX b413", lines[0])
        assertEquals("RX 8813", lines[1])
    }

    @Test
    fun clear_empties() {
        val log = HexLog(capacity = 4)
        log.tx(byteArrayOf(1)); log.rx(byteArrayOf(2))
        log.clear()
        assertEquals(0, log.size())
        assertTrue(log.snapshot().isEmpty())
    }

    @Test
    fun storedBytesAreDefensiveCopies() {
        val log = HexLog(capacity = 4)
        val mutable = byteArrayOf(1, 2, 3)
        log.tx(mutable)
        mutable[0] = 99
        assertEquals(1.toByte(), log.snapshot()[0].bytes[0]) // stored copy unaffected
    }
}

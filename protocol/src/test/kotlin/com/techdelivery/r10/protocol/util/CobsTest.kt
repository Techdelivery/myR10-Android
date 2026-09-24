package com.techdelivery.r10.protocol.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CobsTest {

    private fun rt(input: ByteArray): Boolean = Cobs.decode(Cobs.encode(input)).contentEquals(input)

    @Test
    fun roundTrip_typicalInputs() {
        val cases = listOf(
            ByteArray(0),
            byteArrayOf(0),
            byteArrayOf(0, 0, 0),
            byteArrayOf(0x41),
            byteArrayOf(1, 2, 3, 0, 4, 5),
            byteArrayOf(0, 0x41, 0),
            byteArrayOf(0, 0, 1, 0),
            byteArrayOf(1, 0, 0, 0, 2),
        )
        for (c in cases) {
            assertTrue("expected round-trip for ${c.toList()}", rt(c))
        }
    }

    @Test
    fun roundTrip_randomized_withZerosBreakingRuns() {
        val rnd = Random(12345)
        repeat(300) {
            // Cap length at 253 so no non-zero run can reach the 255 guard quirk.
            val len = rnd.nextInt(0, 254)
            val arr = ByteArray(len) { if (rnd.nextInt(10) == 0) 0 else (rnd.nextInt(1, 256)).toByte() }
            assertTrue("randomized round-trip failed for len=$len", rt(arr))
        }
    }

    @Test
    fun boundary_254_nonZero_roundTrips() {
        val input = ByteArray(254) { 0x41 }
        assertTrue(rt(input))
        assertEquals(255, Cobs.encode(input).size)
    }

    @Test
    fun boundary_255_nonZero_pinsTheGuardQuirk() {
        // DESIGN §5.1 `!= 255` guard: final distance byte is skipped, one byte lost.
        val input = ByteArray(255) { 0x41 }
        val enc = Cobs.encode(input)
        val dec = Cobs.decode(enc)
        assertEquals(255, enc.size)
        assertEquals(254, dec.size)
        assertFalse("255-run must NOT round-trip (reference quirk)", rt(input))
    }

    @Test
    fun boundary_256_nonZero_pinsTheGuardQuirk() {
        val input = ByteArray(256) { 0x41 }
        val enc = Cobs.encode(input)
        val dec = Cobs.decode(enc)
        assertEquals(257, enc.size)
        assertEquals(255, dec.size)
        assertFalse(rt(input))
    }

    @Test
    fun decode_malformed_returnsEmpty_neverThrows() {
        // distance points past end
        assertArrayEquals(ByteArray(0), Cobs.decode(byteArrayOf(0x05, 0x01, 0x02)))
        // distance < 1
        assertArrayEquals(ByteArray(0), Cobs.decode(byteArrayOf(0x00)))
        // truncated multi-block
        assertArrayEquals(ByteArray(0), Cobs.decode(byteArrayOf(0x03, 0x41)))
    }

    @Test
    fun decode_neverThrows_onRandomGarbage() {
        val rnd = Random(99)
        repeat(500) {
            val arr = ByteArray(rnd.nextInt(0, 40)) { rnd.nextInt(0, 256).toByte() }
            // Must not throw; result may be empty or partial.
            Cobs.decode(arr)
        }
    }
}

package com.techdelivery.r10.protocol.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Crc16Test {

    @Test
    fun checkVector_123456789_is_BB3D() {
        assertEquals(0xBB3D, Crc16.compute("123456789".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun checkVector_emitsLittleEndianBytes() {
        assertArrayEquals(
            byteArrayOf(0x3D, 0xBB.toByte()),
            Crc16.computeLe("123456789".toByteArray(Charsets.US_ASCII))
        )
    }

    @Test
    fun emptyInput_isZero() {
        assertEquals(0, Crc16.compute(ByteArray(0)))
    }

    @Test
    fun offsetAndLength_subrange() {
        val full = "123456789".toByteArray(Charsets.US_ASCII)
        val expected = Crc16.compute(full)
        // Same bytes via explicit full range
        assertEquals(expected, Crc16.compute(full, offset = 0, length = full.size))
        // A different subrange gives a different CRC (offset 1, len 8 == "23456789")
        val sub = Crc16.compute(full, offset = 1, length = 8)
        assertEquals(Crc16.compute("23456789".toByteArray(Charsets.US_ASCII)), sub)
    }

    @Test
    fun notXmodem_notBeeef() {
        // Guard against the two wrong references called out in DESIGN §5.1.
        val crc = Crc16.compute("123456789".toByteArray(Charsets.US_ASCII))
        assertEquals(false, crc == 0xBEEF)
        assertEquals(false, crc == 0x31C3) // XMODEM/crc_hqx
    }
}

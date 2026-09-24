package com.techdelivery.r10.protocol.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ByteUtilTest {

    @Test
    fun u16le_pinsLittleEndianByteOrder() {
        assertArrayEquals(byteArrayOf(0x02, 0x01), ByteUtil.u16le(0x0102))
        assertArrayEquals(byteArrayOf(0x00, 0x00), ByteUtil.u16le(0))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xFF.toByte()), ByteUtil.u16le(0xFFFF))
    }

    @Test
    fun u32le_pinsLittleEndianByteOrder() {
        assertArrayEquals(
            byteArrayOf(0x04, 0x03, 0x02, 0x01),
            ByteUtil.u32le(0x01020304L)
        )
        assertArrayEquals(
            byteArrayOf(0x11, 0x22, 0x33, 0x44),
            ByteUtil.u32le(0x44332211L)
        )
    }

    @Test
    fun readU16le_roundTrips() {
        for (v in listOf(0, 1, 0x00FF, 0x0102, 0xFFFF)) {
            assertEquals(v, ByteUtil.readU16le(ByteUtil.u16le(v)))
        }
    }

    @Test
    fun readU32le_roundTrips_withOffset() {
        val bytes = byteArrayOf(0, 0, 0x78, 0x56, 0x34, 0x12.toByte())
        assertEquals(0x12345678L, ByteUtil.readU32le(bytes, offset = 2))
    }

    @Test
    fun hex_isCaseInsensitive_andIgnoresSeparators() {
        assertArrayEquals(
            byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte()),
            ByteUtil.fromHex("aB cD-eF:")
        )
        assertEquals("abcdef", ByteUtil.toHex(byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte())))
        assertEquals("b4 13", ByteUtil.toHex(byteArrayOf(0xB4.toByte(), 0x13), sep = " "))
    }

    @Test
    fun hex_roundTrip() {
        val src = ByteArray(256) { it.toByte() }
        assertEquals(src.toList(), ByteUtil.fromHex(ByteUtil.toHex(src)).toList())
    }

    @Test
    fun concat_joinsInOrder() {
        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4, 5),
            ByteUtil.concat(byteArrayOf(1, 2, 3), byteArrayOf(4, 5))
        )
        assertArrayEquals(byteArrayOf(), ByteUtil.concat())
    }

    @Test(expected = IllegalArgumentException::class)
    fun u16le_rejectsOutOfRange() {
        ByteUtil.u16le(0x1_0000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun fromHex_rejectsOddLength() {
        ByteUtil.fromHex("abc")
    }
}

package com.techdelivery.r10.protocol.wire

import com.techdelivery.r10.protocol.util.ByteUtil
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WireConstantsTest {

    @Test
    fun handshakeLiterals_matchDesignHex() {
        assertEquals("000000000000000000010000", ByteUtil.toHex(WireConstants.HANDSHAKE_FIRST_WRITE))
        assertEquals("010000000000000000010000", ByteUtil.toHex(WireConstants.HANDSHAKE_REPLY_PREFIX))
        assertEquals(12, WireConstants.HANDSHAKE_HEADER_INDEX)
        assertEquals(12, WireConstants.HANDSHAKE_FIRST_WRITE.size)
    }

    @Test
    fun typePairs_areRawBytes() {
        assertArrayEquals(byteArrayOf(0xA0.toByte(), 0x13), WireConstants.TYPE_A0)
        assertArrayEquals(byteArrayOf(0xBA.toByte(), 0x13), WireConstants.TYPE_BA)
        assertArrayEquals(byteArrayOf(0xB4.toByte(), 0x13), WireConstants.TYPE_B4)
        assertArrayEquals(byteArrayOf(0xB3.toByte(), 0x13), WireConstants.TYPE_B3)
        assertArrayEquals(byteArrayOf(0x88.toByte(), 0x13), WireConstants.TYPE_ACK)
    }

    @Test
    fun isType_matchesOnlyLeadingPair() {
        val msg = byteArrayOf(0xB4.toByte(), 0x13, 0xA0.toByte(), 0x13)
        assertTrue(WireConstants.isType(msg, WireConstants.TYPE_B4))
        assertFalse(WireConstants.isType(msg, WireConstants.TYPE_A0))
        assertFalse(WireConstants.isType(byteArrayOf(0x00), WireConstants.TYPE_B4))
    }

    @Test
    fun chunkAndOffsets() {
        assertEquals(19, WireConstants.CHUNK_SIZE)
        assertEquals(16, WireConstants.INBOUND_PROTO_OFFSET)
        assertEquals(14, WireConstants.ACK_COUNTER_TAIL.size)
    }
}

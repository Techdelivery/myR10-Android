package com.techdelivery.r10.protocol.wire

import com.techdelivery.r10.protocol.util.ByteUtil
import com.techdelivery.r10.protocol.util.Cobs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FramingTest {

    private val sampleMsg = byteArrayOf(0xB3.toByte(), 0x13, 0x00, 0x00)

    @Test
    fun frame_lengthFieldEqualsFrameSize() {
        val f = Framing.frame(sampleMsg)
        assertEquals(f.size, ByteUtil.readU16le(f, 0))
    }

    @Test
    fun frame_layoutIsLenMsgCrc() {
        val f = Framing.frame(sampleMsg)
        // head = LE16(len) || msg
        assertEquals(0x08.toByte(), f[0]) // len = 2+4+2 = 8
        assertEquals(0x00.toByte(), f[1])
        assertMsgAt(f, sampleMsg)
    }

    private fun assertMsgAt(frame: ByteArray, msg: ByteArray) {
        for (i in msg.indices) assertEquals(msg[i], frame[2 + i])
    }

    @Test
    fun unframe_roundTrips() {
        for (msg in listOf(
            sampleMsg,
            ByteArray(0),
            ByteArray(1) { 0x42 },
            ByteArray(100) { (it % 251).toByte() },
        )) {
            val f = Framing.frame(msg)
            assertEquals(msg.toList(), Framing.unframe(f)?.toList())
        }
    }

    @Test
    fun unframe_rejectsCorruptCrc() {
        val f = Framing.frame(sampleMsg)
        f[3] = (f[3] + 1).toByte() // corrupt a msg byte
        assertNull(Framing.unframe(f))
    }

    @Test
    fun unframe_rejectsBadLength() {
        val f = Framing.frame(sampleMsg)
        f[0] = 0x99.toByte() // wrong length
        assertNull(Framing.unframe(f))
    }

    @Test
    fun sliceToChunks_allSlicesAtMost19_andReassembleToStream() {
        val msg = ByteArray(60) { (it % 200 + 1).toByte() }
        val chunks = Framing.sliceToChunks(msg)
        assertTrue(chunks.isNotEmpty())
        for (c in chunks) assertTrue("slice too big: ${c.size}", c.size <= 19)
        val stream = ByteUtil.concat(*chunks.toTypedArray())
        val expected = ByteUtil.concat(byteArrayOf(0x00), Cobs.encode(Framing.frame(msg)), byteArrayOf(0x00))
        assertArrayEquals(expected, stream)
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        org.junit.Assert.assertArrayEquals(expected, actual)
    }
}

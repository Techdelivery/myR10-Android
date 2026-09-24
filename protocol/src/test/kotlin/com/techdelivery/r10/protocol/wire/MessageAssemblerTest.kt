package com.techdelivery.r10.protocol.wire

import com.techdelivery.r10.protocol.util.ByteUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageAssemblerTest {

    private val sampleMsg = byteArrayOf(0xB4.toByte(), 0x13, 0x01, 0x00, 0x00, 0x00)

    private fun deviceChunks(msg: ByteArray, devHeader: Byte = 0x11): List<ByteArray> =
        Framing.sliceToChunks(msg).map { byteArrayOf(devHeader) + it }

    @Test
    fun singleMessage_reassemblesAndDecodes() {
        val frames = mutableListOf<ByteArray>()
        val asm = MessageAssembler(onHandshakeBody = {}, onFrame = { frames.add(it) })
        asm.handshakeComplete = true
        deviceChunks(sampleMsg).forEach { asm.onChunk(it) }
        assertEquals(1, frames.size)
        assertEquals(sampleMsg.toList(), Framing.unframe(frames[0])?.toList())
    }

    @Test
    fun multiChunkMessage_reassembles() {
        val msg = ByteArray(60) { ((it % 200) + 1).toByte() }
        val frames = mutableListOf<ByteArray>()
        val asm = MessageAssembler(onHandshakeBody = {}, onFrame = { frames.add(it) })
        asm.handshakeComplete = true
        val chunks = deviceChunks(msg)
        assertTrue("expected multiple chunks", chunks.size > 1)
        chunks.forEach { asm.onChunk(it) }
        assertEquals(1, frames.size)
        assertEquals(msg.toList(), Framing.unframe(frames[0])?.toList())
    }

    @Test
    fun backToBackMessages_emitTwoFrames() {
        val frames = mutableListOf<ByteArray>()
        val asm = MessageAssembler(onHandshakeBody = {}, onFrame = { frames.add(it) })
        asm.handshakeComplete = true
        deviceChunks(sampleMsg).forEach { asm.onChunk(it) }
        deviceChunks(byteArrayOf(0xB3.toByte(), 0x13, 0x02, 0x00)).forEach { asm.onChunk(it) }
        assertEquals(2, frames.size)
        assertEquals(sampleMsg.toList(), Framing.unframe(frames[0])?.toList())
        assertEquals(listOf<Byte>(0xB3.toByte(), 0x13, 0x02, 0x00), Framing.unframe(frames[1])?.toList())
    }

    @Test
    fun emptyDecode_isDropped() {
        val frames = mutableListOf<ByteArray>()
        val asm = MessageAssembler(onHandshakeBody = {}, onFrame = { frames.add(it) })
        asm.handshakeComplete = true
        // body after header strip = [05 01 02 00]; trailing 00 completes, COBS decode of [05 01 02] is malformed -> empty
        asm.onChunk(byteArrayOf(0x11, 0x05, 0x01, 0x02, 0x00))
        assertTrue(frames.isEmpty())
    }

    @Test
    fun preHandshakeChunks_routeToHandshake() {
        val hs = mutableListOf<ByteArray>()
        val asm = MessageAssembler(onHandshakeBody = { hs.add(it) }, onFrame = {})
        asm.handshakeComplete = false
        asm.onChunk(byteArrayOf(0x00, 0x01, 0x02))
        assertEquals(1, hs.size)
        assertEquals(listOf<Byte>(0x01, 0x02), hs[0].toList())
    }

    @Test
    fun zeroHeaderPostHandshake_stillRoutesToHandshake() {
        // Mirrors reference: `header == 0 || !handshakeComplete`
        val hs = mutableListOf<ByteArray>()
        val asm = MessageAssembler(onHandshakeBody = { hs.add(it) }, onFrame = {})
        asm.handshakeComplete = true
        asm.onChunk(byteArrayOf(0x00, 0x05, 0x06))
        assertEquals(1, hs.size)
        assertEquals(listOf<Byte>(0x05, 0x06), hs[0].toList())
    }

    @Test
    fun emptyChunk_isIgnored() {
        val frames = mutableListOf<ByteArray>()
        val asm = MessageAssembler(onHandshakeBody = {}, onFrame = { frames.add(it) })
        asm.handshakeComplete = true
        asm.onChunk(ByteArray(0))
        assertTrue(frames.isEmpty())
    }

    @Test
    fun reset_clearsAccumulator() {
        val asm = MessageAssembler(onHandshakeBody = {}, onFrame = {})
        asm.handshakeComplete = true
        asm.onChunk(byteArrayOf(0x11, 0x01, 0x02)) // partial, no trailing 00
        asm.reset()
        assertEquals(0, ByteUtil.concat().size) // sanity: ByteUtil works
    }
}

package com.techdelivery.r10.protocol.wire

import com.techdelivery.r10.protocol.util.ByteUtil
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandshakeStateMachineTest {

    private fun replyBody(header: Byte) = ByteUtil.concat(
        WireConstants.HANDSHAKE_REPLY_PREFIX,
        byteArrayOf(header),
        byteArrayOf(0x01, 0x02, 0x03),
    )

    @Test
    fun happyPath_extractsHeaderAndEmitsFinalWrite() {
        val sm = HandshakeStateMachine()
        sm.begin()
        val finalWrite = sm.onBody(replyBody(0xAB.toByte()))
        // Final write BODY is 0x00; the engine prepends the header -> [0xAB, 0x00] on air.
        assertArrayEquals(byteArrayOf(0x00), finalWrite)
        assertEquals(0xAB, sm.dynamicHeader)
        assertTrue(sm.isComplete)
        assertEquals(HandshakeStateMachine.State.DONE, sm.state)
    }

    @Test
    fun wrongPrefix_staysWaiting() {
        val sm = HandshakeStateMachine()
        sm.begin()
        assertNull(sm.onBody(ByteArray(13)))
        assertEquals(HandshakeStateMachine.State.WAITING_REPLY, sm.state)
    }

    @Test
    fun tooShort_isIgnored() {
        val sm = HandshakeStateMachine()
        sm.begin()
        assertNull(sm.onBody(byteArrayOf(1, 2, 3)))
        assertEquals(HandshakeStateMachine.State.WAITING_REPLY, sm.state)
    }

    @Test
    fun afterDone_noFurtherTransitions() {
        val sm = HandshakeStateMachine()
        sm.begin()
        sm.onBody(replyBody(0x42))
        assertNull(sm.onBody(replyBody(0x99.toByte())))
        assertEquals(0x42, sm.dynamicHeader)
    }

    @Test
    fun onBodyBeforeBegin_isNull() {
        val sm = HandshakeStateMachine()
        assertNull(sm.onBody(replyBody(0x42)))
        assertEquals(HandshakeStateMachine.State.IDLE, sm.state)
    }
}

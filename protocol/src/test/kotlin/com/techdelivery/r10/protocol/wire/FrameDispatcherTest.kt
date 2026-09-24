package com.techdelivery.r10.protocol.wire

import LaunchMonitor.Proto.R10Protos
import com.techdelivery.r10.protocol.util.ByteUtil
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameDispatcherTest {

    // msg = type(2) || counter(2) || reserved(12) || proto
    private fun buildMsg(type: ByteArray, counter: Int, protoBytes: ByteArray = ByteArray(0)): ByteArray =
        ByteUtil.concat(type, ByteUtil.u16le(counter), ByteArray(12), protoBytes)

    private val emptyProto = R10Protos.WrapperProto.newBuilder().build().toByteArray()

    @Test
    fun buildAck_A0_isBaseBody() {
        val ack = FrameDispatcher().buildAck(buildMsg(WireConstants.TYPE_A0, 0))
        assertArrayEquals(ByteUtil.fromHex("8813A01300"), ack)
    }

    @Test
    fun buildAck_BA_isBaseBody() {
        val ack = FrameDispatcher().buildAck(buildMsg(WireConstants.TYPE_BA, 0))
        assertArrayEquals(ByteUtil.fromHex("8813BA1300"), ack)
    }

    @Test
    fun buildAck_B4_appendsCounterAndTail() {
        val ack = FrameDispatcher().buildAck(buildMsg(WireConstants.TYPE_B4, 0x0201))
        // 88 13 B4 13 00 | LE16(0x0201)=01 02 | 14 zeros
        assertArrayEquals(ByteUtil.fromHex("8813B413000102" + "00".repeat(14)), ack)
        assertEquals(21, ack.size)
    }

    @Test
    fun buildAck_B3_appendsCounterAndTail() {
        val ack = FrameDispatcher().buildAck(buildMsg(WireConstants.TYPE_B3, 7))
        // LE16(7) = 07 00
        assertArrayEquals(ByteUtil.fromHex("8813B313000700" + "00".repeat(14)), ack)
    }

    @Test
    fun dispatch_A0_infoAck() {
        val frame = Framing.frame(buildMsg(WireConstants.TYPE_A0, 0))
        val d = FrameDispatcher().dispatch(frame, expectedCounter = 0)
        assertTrue(d is FrameDispatcher.Dispatch.InfoAck)
        assertEquals("A013", (d as FrameDispatcher.Dispatch.InfoAck).type)
        assertArrayEquals(ByteUtil.fromHex("8813A01300"), d.ackPayload)
    }

    @Test
    fun dispatch_B4_matchingCounter_response() {
        val frame = Framing.frame(buildMsg(WireConstants.TYPE_B4, 5, emptyProto))
        val d = FrameDispatcher().dispatch(frame, expectedCounter = 5)
        assertTrue(d is FrameDispatcher.Dispatch.Response)
        assertEquals(5, (d as FrameDispatcher.Dispatch.Response).counter)
        assertEquals(emptyProto.toList(), d.proto.toByteArray().toList())
    }

    @Test
    fun dispatch_B4_staleCounter_ackedNotCompleted() {
        val frame = Framing.frame(buildMsg(WireConstants.TYPE_B4, 9, emptyProto))
        val d = FrameDispatcher().dispatch(frame, expectedCounter = 5)
        assertTrue(d is FrameDispatcher.Dispatch.InfoAck)
        assertEquals("B413-stale", (d as FrameDispatcher.Dispatch.InfoAck).type)
    }

    @Test
    fun dispatch_B3_deviceRequest() {
        val frame = Framing.frame(buildMsg(WireConstants.TYPE_B3, 0, emptyProto))
        val d = FrameDispatcher().dispatch(frame, expectedCounter = 0)
        assertTrue(d is FrameDispatcher.Dispatch.DeviceRequest)
    }

    @Test
    fun dispatch_unknownType_unknownAck() {
        val frame = Framing.frame(buildMsg(byteArrayOf(0x99.toByte(), 0x13), 0))
        val d = FrameDispatcher().dispatch(frame, expectedCounter = 0)
        assertTrue(d is FrameDispatcher.Dispatch.UnknownAck)
        assertEquals("9913", (d as FrameDispatcher.Dispatch.UnknownAck).typeHex)
    }

    @Test
    fun dispatch_corruptCrc_droppedNoAck() {
        val frame = Framing.frame(buildMsg(WireConstants.TYPE_A0, 0))
        frame[3] = (frame[3] + 1).toByte() // corrupt
        val d = FrameDispatcher().dispatch(frame, expectedCounter = 0)
        assertTrue(d is FrameDispatcher.Dispatch.Dropped)
        assertNull(d.ackPayload)
    }
}

package com.techdelivery.r10.protocol

import LaunchMonitor.Proto.R10Protos
import com.techdelivery.r10.protocol.util.ByteUtil
import com.techdelivery.r10.protocol.wire.Framing
import com.techdelivery.r10.protocol.wire.WireConstants
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E3 — request/response correlation over a scripted FakeTransport session.
 * This is the regression net for hardware day (DESIGN §5.4-§5.7).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProtocolEngineTest {

    private val dynHeader: Byte = 0xAB.toByte()
    private val replyBody = ByteUtil.concat(
        WireConstants.HANDSHAKE_REPLY_PREFIX,
        byteArrayOf(dynHeader),
        byteArrayOf(0x01, 0x02, 0x03),
    )
    private val emptyProto = R10Protos.WrapperProto.newBuilder().build()

    private fun buildMsg(type: ByteArray, counter: Int, protoBytes: ByteArray = ByteArray(0)): ByteArray =
        ByteUtil.concat(type, ByteUtil.u16le(counter), ByteArray(12), protoBytes)

    private suspend fun FakeTransport.emitFramed(msg: ByteArray, header: Byte) {
        for (slice in Framing.sliceToChunks(msg)) emit(byteArrayOf(header) + slice)
    }

    @Test
    fun handshakeCompletesAndWritesFinalRawWrite() = runTest {
        val fake = FakeTransport()
        val engine = ProtocolEngine(fake, scope = this)
        engine.start()
        runCurrent()
        // first handshake write on air = [0x00] + 12-byte literal
        assertTrue(
            fake.writes.any { it.contentEquals(byteArrayOf(0x00) + WireConstants.HANDSHAKE_FIRST_WRITE) },
        )
        // device reply (header 0x00 -> routed to handshake)
        fake.emit(byteArrayOf(0x00) + replyBody)
        runCurrent()
        assertTrue(engine.isHandshakeComplete)
        // final handshake write on air = [H, 0x00]
        assertTrue(fake.writes.any { it.contentEquals(byteArrayOf(dynHeader, 0x00)) })
        engine.stop()
    }

    @Test
    fun requestResponse_incrementsCounterExactlyOnce() = runTest {
        val fake = FakeTransport()
        val engine = ProtocolEngine(fake, scope = this)
        engine.start()
        runCurrent()
        fake.emit(byteArrayOf(0x00) + replyBody)
        runCurrent()
        assertEquals(0, engine.currentRequestCounter)

        val respJob = async { engine.sendProtobufRequest(emptyProto) }
        runCurrent() // request written

        // device replies B4 with matching counter 0
        fake.emitFramed(buildMsg(WireConstants.TYPE_B4, 0, emptyProto.toByteArray()), dynHeader)
        runCurrent()

        val resp = respJob.await()
        assertNotNull(resp)
        assertEquals(0, resp!!.counter)
        assertEquals(1, engine.currentRequestCounter)
        engine.stop()
    }

    @Test
    fun requestTimeout_leavesCounterUnchanged() = runTest {
        val fake = FakeTransport()
        val engine = ProtocolEngine(fake, scope = this)
        engine.start()
        runCurrent()
        fake.emit(byteArrayOf(0x00) + replyBody)
        runCurrent()
        val before = engine.currentRequestCounter

        val resp = engine.sendProtobufRequest(emptyProto) // no B4 reply -> timeout
        assertNull(resp)
        assertEquals(before, engine.currentRequestCounter)
        engine.stop()
    }

    @Test
    fun deviceRequest_emitsEventNotification() = runTest {
        val fake = FakeTransport()
        val engine = ProtocolEngine(fake, scope = this)
        val events = mutableListOf<R10Protos.WrapperProto>()
        val evJob = launch { engine.eventNotification.collect { events.add(it) } }
        engine.start()
        runCurrent()
        fake.emit(byteArrayOf(0x00) + replyBody)
        runCurrent()

        fake.emitFramed(buildMsg(WireConstants.TYPE_B3, 0, emptyProto.toByteArray()), dynHeader)
        runCurrent()

        assertEquals(1, events.size)
        engine.stop()
        evJob.cancel()
    }

    @Test
    fun ackIsSentForEveryReceivedFrame() = runTest {
        val fake = FakeTransport()
        val engine = ProtocolEngine(fake, scope = this)
        engine.start()
        runCurrent()
        fake.emit(byteArrayOf(0x00) + replyBody)
        runCurrent()
        val writesBefore = fake.writes.size

        // A013 device-info frame -> engine must emit an ack (framed)
        fake.emitFramed(buildMsg(WireConstants.TYPE_A0, 0), dynHeader)
        runCurrent()

        assertTrue("expected an ack write after the A0 frame", fake.writes.size > writesBefore)
        engine.stop()
    }
}

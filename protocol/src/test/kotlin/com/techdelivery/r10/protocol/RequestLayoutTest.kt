package com.techdelivery.r10.protocol

import LaunchMonitor.Proto.R10Protos
import com.techdelivery.r10.protocol.util.ByteUtil
import com.techdelivery.r10.protocol.util.Cobs
import com.techdelivery.r10.protocol.wire.Framing
import com.techdelivery.r10.protocol.wire.WireConstants
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * H2 hardware regression pin — the bug that cost a whole device session.
 *
 * DESIGN §5.7 documented the app->device request counter as **LE16** with the
 * protobuf at offset **14**, and explicitly warned that the two directions were
 * deliberately asymmetric. That is wrong. The reference implementation
 * (`gsp-r10-adapter` `BaseDevice.cs:280`) declares the counter as a C# `int`,
 * so `BitConverter.GetBytes(int)` emits **four** bytes and the proto lands at
 * offset **16** — symmetric with inbound frames (§5.5).
 *
 * With a 2-byte counter the device still validates the frame (CRC is good) and
 * acks it, but cannot parse the proto, so it **never replies with B413**. The
 * failure is silent: every setup request just burns its 5 s timeout while the
 * link looks perfectly healthy.
 *
 * The existing correlation tests pass under either layout because they never
 * assert on-air byte offsets — which is exactly why this survived to hardware
 * day. This test pins the actual bytes on the wire.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RequestLayoutTest {

    private val dynHeader: Byte = 0x07

    private fun wakeUpProto(): R10Protos.WrapperProto = R10Protos.WrapperProto.newBuilder()
        .setService(
            R10Protos.LaunchMonitorService.newBuilder()
                .setWakeUpRequest(R10Protos.WakeUpRequest.getDefaultInstance()),
        )
        .build()

    @Test
    fun requestCounterIsLe32AndProtoStartsAtOffset16() = runTest {
        val fake = FakeTransport()
        val engine = ProtocolEngine(fake, scope = this)
        engine.start()
        runCurrent()

        // Real-device handshake reply: 12-byte prefix + dynamic header 0x07 (§5.4)
        fake.emit(
            byteArrayOf(0x00) + WireConstants.HANDSHAKE_REPLY_PREFIX +
                byteArrayOf(dynHeader, 0x00, 0x00),
        )
        runCurrent()
        assertEquals(true, engine.isHandshakeComplete)

        val proto = wakeUpProto()
        val before = fake.writes.size
        // No B413 is fed back, so this times out — the TX bytes are the assertion.
        engine.sendProtobufRequest(proto)

        val tx = ByteUtil.concat(
            *fake.writes
                .subList(before, fake.writes.size)
                .map { it.copyOfRange(1, it.size) } // strip the dynamic header per chunk
                .toTypedArray(),
        )

        // wire stream = 0x00 || COBS(frame) || 0x00
        val frame = Cobs.decode(tx.copyOfRange(1, tx.size - 1))
        val msg = Framing.unframe(frame)
        assertNotNull("request frame must CRC-verify", msg)
        val m = msg!!

        assertEquals("b313", ByteUtil.toHex(m.copyOfRange(0, 2)))
        // counter occupies msg[2..6) as a FOUR-byte LE value
        assertEquals(0L, ByteUtil.readU32le(m, 2))
        // proto must begin at offset 16, not 14
        assertEquals(16, m.size - proto.serializedSize)
        assertEquals(
            ByteUtil.toHex(proto.toByteArray()),
            ByteUtil.toHex(m.copyOfRange(16, m.size)),
        )
        engine.stop()
    }
}

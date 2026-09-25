package com.techdelivery.r10.protocol

import LaunchMonitor.Proto.R10Protos
import com.techdelivery.r10.protocol.alert.DeviceAlert
import com.techdelivery.r10.protocol.shot.Shot
import com.techdelivery.r10.protocol.util.ByteUtil
import com.techdelivery.r10.protocol.wire.Framing
import com.techdelivery.r10.protocol.wire.MessageAssembler
import com.techdelivery.r10.protocol.wire.WireConstants
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M2 — the §7.2 runtime policies wired through [R10Device.pumpAlerts]:
 * shot dedup by `shot_id`, auto-wake on STANDBY, state tracking.
 */
class R10DeviceAlertPumpTest {

    private val dynHeader: Byte = 0x07
    private val replyBody = ByteUtil.concat(WireConstants.HANDSHAKE_REPLY_PREFIX, byteArrayOf(dynHeader))

    private fun b3Msg(proto: R10Protos.WrapperProto, counter: Int = 0): ByteArray =
        ByteUtil.concat(WireConstants.TYPE_B3, ByteUtil.u16le(counter), ByteArray(12), proto.toByteArray())

    private suspend fun FakeTransport.emitFramed(msg: ByteArray, header: Byte = dynHeader) {
        for (slice in Framing.sliceToChunks(msg)) emit(byteArrayOf(header) + slice)
    }

    private fun shotWrapper(id: Int): R10Protos.WrapperProto =
        R10Protos.WrapperProto.newBuilder()
            .setEvent(
                R10Protos.EventSharing.newBuilder().setNotification(
                    R10Protos.AlertNotification.newBuilder().setAlertNotification(
                        R10Protos.AlertDetails.newBuilder().setMetrics(
                            R10Protos.Metrics.newBuilder()
                                .setShotId(id)
                                .setBallMetrics(R10Protos.BallMetrics.newBuilder().setBallSpeed(45f)),
                        ),
                    ),
                ),
            ).build()

    private fun stateWrapper(state: R10Protos.State.StateType): R10Protos.WrapperProto =
        R10Protos.WrapperProto.newBuilder()
            .setEvent(
                R10Protos.EventSharing.newBuilder().setNotification(
                    R10Protos.AlertNotification.newBuilder().setAlertNotification(
                        R10Protos.AlertDetails.newBuilder().setState(R10Protos.State.newBuilder().setState(state)),
                    ),
                ),
            ).build()

    /** Decode every app->device B313 request proto out of the raw on-air chunks. */
    private suspend fun writtenRequestProtos(chunks: List<ByteArray>): List<R10Protos.WrapperProto> {
        val out = mutableListOf<R10Protos.WrapperProto>()
        val asm = MessageAssembler(onHandshakeBody = {}, onFrame = { frame ->
            val msg = Framing.unframe(frame)
            if (msg != null && msg.size > WireConstants.INBOUND_PROTO_OFFSET &&
                WireConstants.isType(msg, WireConstants.TYPE_B3)
            ) {
                out.add(R10Protos.WrapperProto.parseFrom(msg.copyOfRange(WireConstants.INBOUND_PROTO_OFFSET, msg.size)))
            }
        })
        asm.handshakeComplete = true
        chunks.forEach { asm.onChunk(it) }
        return out
    }

    private class Harness(
        val fake: FakeTransport,
        val device: R10Device,
        val engine: ProtocolEngine,
        val pump: kotlinx.coroutines.Job,
    )

    private suspend fun TestScope.harness(autoWake: Boolean): Harness {
        val fake = FakeTransport()
        val engine = ProtocolEngine(fake, scope = this)
        val device = R10Device(fake, engine, DeviceSetupConfig(autoWake = autoWake))
        val pump = device.pumpAlerts(this)
        engine.start()
        runCurrent()
        fake.emit(byteArrayOf(0x00) + replyBody)   // handshake reply
        runCurrent()
        assertTrue("handshake must complete before the pump is exercised", engine.isHandshakeComplete)
        return Harness(fake, device, engine, pump)
    }

    /** runTest waits for children, so every collector must be torn down. */
    private fun TestScope.finish(h: Harness) {
        h.engine.stop()
        coroutineContext.job.cancelChildren()
    }

    @Test
    fun duplicateShotIdIsDeliveredOnce() = runTest {
        val h = harness(autoWake = true)
        val shots = mutableListOf<Shot>()
        val alerts = mutableListOf<DeviceAlert>()
        launch { h.device.shots.collect { shots.add(it) } }
        launch { h.device.alerts.collect { alerts.add(it) } }
        runCurrent()

        h.fake.emitFramed(b3Msg(shotWrapper(5)))
        runCurrent()
        h.fake.emitFramed(b3Msg(shotWrapper(5)))   // device re-pushes the same shot
        runCurrent()
        h.fake.emitFramed(b3Msg(shotWrapper(6)))
        runCurrent()

        assertEquals("duplicate shot_id must be ignored", listOf(5, 6), shots.map { it.shotId })
        assertEquals("raw alerts still surface both notifications", 3, alerts.count { it is DeviceAlert.ShotAlert })
        assertEquals(100.6605, shots[0].ball!!.ballSpeedMph, 1e-3) // 45 m/s * 2.2369
        finish(h)
    }

    @Test
    fun standbyWithAutoWakeSendsWakeUpRequest() = runTest {
        val h = harness(autoWake = true)
        val states = mutableListOf<R10Protos.State.StateType?>()
        launch { h.device.lastStateType.collect { states.add(it) } }
        runCurrent()

        h.fake.emitFramed(b3Msg(stateWrapper(R10Protos.State.StateType.STANDBY)))
        runCurrent()
        // auto-wake must have issued a WakeUpRequest on air; answer it so the counter advances
        h.fake.emitFramed(
            ByteUtil.concat(WireConstants.TYPE_B4, ByteUtil.u16le(0), ByteArray(12)),
        )
        runCurrent()

        val reqs = writtenRequestProtos(h.fake.writes)
        assertTrue(
            "expected a wake_up_request to be sent on STANDBY with autoWake on",
            reqs.any { it.hasService() && it.service.hasWakeUpRequest() },
        )
        assertEquals(1, h.engine.currentRequestCounter)
        assertTrue(states.contains(R10Protos.State.StateType.STANDBY))
        finish(h)
    }

    @Test
    fun standbyWithoutAutoWakeDoesNotWake() = runTest {
        val h = harness(autoWake = false)
        h.fake.emitFramed(b3Msg(stateWrapper(R10Protos.State.StateType.STANDBY)))
        runCurrent()

        val reqs = writtenRequestProtos(h.fake.writes)
        assertTrue(
            "autoWake=false must not send a wake_up_request",
            reqs.none { it.hasService() && it.service.hasWakeUpRequest() },
        )
        assertEquals(0, h.engine.currentRequestCounter)
        assertEquals(R10Protos.State.StateType.STANDBY, h.device.lastStateType.value)
        finish(h)
    }

    @Test
    fun waitingStateDoesNotTriggerWake() = runTest {
        val h = harness(autoWake = true)
        h.fake.emitFramed(b3Msg(stateWrapper(R10Protos.State.StateType.WAITING)))
        runCurrent()
        val reqs = writtenRequestProtos(h.fake.writes)
        assertTrue(reqs.none { it.hasService() && it.service.hasWakeUpRequest() })
        assertEquals(R10Protos.State.StateType.WAITING, h.device.lastStateType.value)
        finish(h)
    }

    @Test
    fun resetForNewConnectionAllowsReusedShotId() = runTest {
        val h = harness(autoWake = false)
        val shots = mutableListOf<Shot>()
        launch { h.device.shots.collect { shots.add(it) } }
        runCurrent()

        h.fake.emitFramed(b3Msg(shotWrapper(9)))
        runCurrent()
        h.device.resetForNewConnection()
        h.fake.emitFramed(b3Msg(shotWrapper(9)))
        runCurrent()

        assertEquals(listOf(9, 9), shots.map { it.shotId })
        finish(h)
    }
}

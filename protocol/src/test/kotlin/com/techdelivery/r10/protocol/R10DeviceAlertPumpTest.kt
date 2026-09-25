package com.techdelivery.r10.protocol

import LaunchMonitor.Proto.R10Protos
import com.techdelivery.r10.protocol.alert.DeviceAlert
import com.techdelivery.r10.protocol.shot.Shot
import com.techdelivery.r10.protocol.util.ByteUtil
import com.techdelivery.r10.protocol.wire.Framing
import com.techdelivery.r10.protocol.wire.MessageAssembler
import com.techdelivery.r10.protocol.wire.WireConstants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
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
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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

    /** Same as [shotWrapper] but with `shot_id` left unset (proto2 `optional uint32`). */
    private fun shotWrapperNoId(ballSpeed: Float = 45f): R10Protos.WrapperProto =
        R10Protos.WrapperProto.newBuilder()
            .setEvent(
                R10Protos.EventSharing.newBuilder().setNotification(
                    R10Protos.AlertNotification.newBuilder().setAlertNotification(
                        R10Protos.AlertDetails.newBuilder().setMetrics(
                            R10Protos.Metrics.newBuilder()
                                .setBallMetrics(R10Protos.BallMetrics.newBuilder().setBallSpeed(ballSpeed)),
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
    fun shotsWithoutShotIdAreNotCollapsedIntoOneDuplicate() = runTest {
        val h = harness(autoWake = false)
        val shots = mutableListOf<Shot>()
        launch { h.device.shots.collect { shots.add(it) } }
        runCurrent()

        // Three distinct real shots whose frames carry no shot_id. `getShotId()`
        // reports 0 for all three, so a naive dedup would keep only the first.
        h.fake.emitFramed(b3Msg(shotWrapperNoId(40f)))
        runCurrent()
        h.fake.emitFramed(b3Msg(shotWrapperNoId(41f)))
        runCurrent()
        h.fake.emitFramed(b3Msg(shotWrapperNoId(42f)))
        runCurrent()

        assertEquals(
            "a frame with no shot_id must never be dropped as a duplicate",
            3,
            shots.size,
        )
        assertEquals(listOf(0, 0, 0), shots.map { it.shotId }) // read-back value, not a dedup key
        assertEquals(3, shots.map { it.ball!!.ballSpeedMph }.distinct().size)
        assertTrue(
            "every id-less shot must report hasDeviceShotId=false",
            shots.all { it.shotId == 0 },
        )
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

    /**
     * W1 — the old code launched a fresh `wakeUp()` per STANDBY alert. The
     * engine's send mutex serialises them, so the pile-up is invisible until the
     * in-flight wake is answered: then every queued coroutine fires in a burst.
     * The answer below is what makes the fan-out observable.
     */
    @Test
    fun repeatedStandbyDoesNotFanOutWakeRequests() = runTest {
        val h = harness(autoWake = true)
        repeat(3) {
            h.fake.emitFramed(b3Msg(stateWrapper(R10Protos.State.StateType.STANDBY)))
            runCurrent()
        }
        // Answer the in-flight wake. Without the single-flight guard the two queued
        // wakeUp() coroutines now get their turn and each writes another request.
        h.fake.emitFramed(ByteUtil.concat(WireConstants.TYPE_B4, ByteUtil.u16le(0), ByteArray(12)))
        runCurrent()

        val wakes = writtenRequestProtos(h.fake.writes)
            .count { it.hasService() && it.service.hasWakeUpRequest() }
        assertEquals("three STANDBY alerts must produce one wake request, not a burst", 1, wakes)
        finish(h)
    }

    /**
     * The single-flight guard must not latch. Once the first wake completes, a later
     * STANDBY has to be able to wake the device again.
     */
    @Test
    fun wakeIsRetriedOnALaterStandbyOnceTheFirstCompletes() = runTest {
        val h = harness(autoWake = true)
        h.fake.emitFramed(b3Msg(stateWrapper(R10Protos.State.StateType.STANDBY)))
        runCurrent()
        h.fake.emitFramed(ByteUtil.concat(WireConstants.TYPE_B4, ByteUtil.u16le(0), ByteArray(12)))
        runCurrent()

        h.fake.emitFramed(b3Msg(stateWrapper(R10Protos.State.StateType.STANDBY)))
        runCurrent()

        val wakes = writtenRequestProtos(h.fake.writes)
            .count { it.hasService() && it.service.hasWakeUpRequest() }
        assertEquals("a later STANDBY must wake again", 2, wakes)
        finish(h)
    }

    /**
     * W2 — a wedged alert subscriber must not hold back real shots. The old
     * `_alerts.emit(...)` sat on the same suspend path, so once its 128-slot buffer
     * filled the pump stalled and shot delivery stopped with it. The alert flow is
     * DROP_OLDEST + `tryEmit` now, so the pump never suspends on it.
     */
    @Test
    fun stalledAlertSubscriberDoesNotStallShots() = runTest {
        val h = harness(autoWake = false)
        val gate = CompletableDeferred<Unit>()
        val shots = mutableListOf<Shot>()
        launch {
            h.device.alerts.collect { gate.await() } // permanently wedged consumer
        }
        launch { h.device.shots.collect { shots.add(it) } }
        // Block until the wedged alert subscriber is actually registered. Relying
        // on runCurrent alone made the test depend on scheduler ordering rather
        // than on the behaviour under test: with no subscriber registered the
        // alerts are simply dropped and the pump never had to be proven safe.
        h.device.alertSubscriberCount.first { it > 0 }

        repeat(500) { i ->
            h.fake.emitFramed(b3Msg(shotWrapper(2_000 + i)))
            runCurrent()
        }

        assertEquals(
            "a wedged alert subscriber must not hold back real shots",
            500,
            shots.size,
        )
        gate.complete(Unit)
        finish(h)
    }
}

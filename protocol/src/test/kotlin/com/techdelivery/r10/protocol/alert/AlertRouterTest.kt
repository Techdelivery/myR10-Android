package com.techdelivery.r10.protocol.alert

import LaunchMonitor.Proto.R10Protos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M2 — DESIGN §6 / §7.2: decode `EventSharing.notification` -> `AlertDetails`
 * into the app-facing alert types.
 */
class AlertRouterTest {

    private fun wrap(details: R10Protos.AlertDetails?): R10Protos.WrapperProto {
        val note = R10Protos.AlertNotification.newBuilder()
            .setType(R10Protos.AlertNotification.AlertType.LAUNCH_MONITOR)
        if (details != null) note.setAlertNotification(details)
        return R10Protos.WrapperProto.newBuilder()
            .setEvent(R10Protos.EventSharing.newBuilder().setNotification(note))
            .build()
    }

    @Test
    fun stateOnlyNotificationRoutesToStateChanged() {
        val details = R10Protos.AlertDetails.newBuilder()
            .setState(R10Protos.State.newBuilder().setState(R10Protos.State.StateType.RECORDING))
            .build()
        val out = AlertRouter.route(wrap(details))
        assertEquals(1, out.size)
        assertEquals(R10Protos.State.StateType.RECORDING, (out[0] as DeviceAlert.StateChanged).state)
    }

    @Test
    fun metricsNotificationRoutesToShotAlertWithConvertedUnits() {
        val metrics = R10Protos.Metrics.newBuilder()
            .setShotId(77)
            .setShotType(R10Protos.Metrics.ShotType.NORMAL)
            .setBallMetrics(R10Protos.BallMetrics.newBuilder().setBallSpeed(50f).setTotalSpin(2000f).setSpinAxis(0f))
            .build()
        val out = AlertRouter.route(wrap(R10Protos.AlertDetails.newBuilder().setMetrics(metrics).build()), nowMs = 999L)
        assertEquals(1, out.size)
        val shot = (out[0] as DeviceAlert.ShotAlert).shot
        assertEquals(77, shot.shotId)
        assertEquals(999L, shot.receivedAtMs)
        assertEquals(111.845, shot.ball!!.ballSpeedMph, 1e-3)
        assertEquals(2000.0, shot.ball!!.backSpinRpm, 1e-6)
        assertTrue("present shot_id must be reported", (out[0] as DeviceAlert.ShotAlert).hasDeviceShotId)
    }

    @Test
    fun metricsWithoutShotIdReportsHasDeviceShotIdFalse() {
        val metrics = R10Protos.Metrics.newBuilder()
            .setBallMetrics(R10Protos.BallMetrics.newBuilder().setBallSpeed(50f))
            .build()
        val out = AlertRouter.route(wrap(R10Protos.AlertDetails.newBuilder().setMetrics(metrics).build()))
        val alert = out.single() as DeviceAlert.ShotAlert
        assertTrue("absent shot_id must not be reported as present", !alert.hasDeviceShotId)
        assertEquals(0, alert.shot.shotId) // proto read-back, must not be used as a dedup key
    }

    @Test
    fun errorNotificationCarriesCodeSeverityAndTilt() {
        val err = R10Protos.Error.newBuilder()
            .setCode(R10Protos.Error.ErrorCode.PLATFORM_TILTED)
            .setSeverity(R10Protos.Error.Severity.SERIOUS)
            .setDeviceTilt(R10Protos.Tilt.newBuilder().setRoll(4.5f).setPitch(-1.5f))
            .build()
        val out = AlertRouter.route(wrap(R10Protos.AlertDetails.newBuilder().setError(err).build()))
        val e = out.single() as DeviceAlert.ErrorAlert
        assertEquals(R10Protos.Error.ErrorCode.PLATFORM_TILTED, e.code)
        assertEquals(R10Protos.Error.Severity.SERIOUS, e.severity)
        assertEquals(4.5f, e.rollDeg!!)
        assertEquals(-1.5f, e.pitchDeg!!)
    }

    @Test
    fun errorWithoutTiltYieldsNullAngles() {
        val err = R10Protos.Error.newBuilder()
            .setCode(R10Protos.Error.ErrorCode.OVERHEATING)
            .setSeverity(R10Protos.Error.Severity.FATAL)
            .build()
        val e = AlertRouter.route(wrap(R10Protos.AlertDetails.newBuilder().setError(err).build())).single() as DeviceAlert.ErrorAlert
        assertNull(e.rollDeg)
        assertNull(e.pitchDeg)
    }

    @Test
    fun calibrationNotificationRoutesToCalibrationAlert() {
        val cal = R10Protos.CalibrationStatus.newBuilder()
            .setStatus(R10Protos.CalibrationStatus.StatusType.RECALIBRATION_REQUIRED)
            .setResult(R10Protos.CalibrationStatus.CalibrationResult.UNIT_MOVING)
            .build()
        val c = AlertRouter.route(wrap(R10Protos.AlertDetails.newBuilder().setTiltCalibration(cal).build())).single() as DeviceAlert.CalibrationAlert
        assertEquals(R10Protos.CalibrationStatus.StatusType.RECALIBRATION_REQUIRED, c.status)
        assertEquals(R10Protos.CalibrationStatus.CalibrationResult.UNIT_MOVING, c.result)
    }

    @Test
    fun multiplePayloadsInOneDetailsYieldMultipleAlerts() {
        val details = R10Protos.AlertDetails.newBuilder()
            .setState(R10Protos.State.newBuilder().setState(R10Protos.State.StateType.PROCESSING))
            .setMetrics(R10Protos.Metrics.newBuilder().setShotId(5).build())
            .setError(R10Protos.Error.newBuilder().setCode(R10Protos.Error.ErrorCode.RADAR_SATURATION).build())
            .build()
        val out = AlertRouter.route(wrap(details))
        assertEquals(3, out.size)
        assertTrue(out[0] is DeviceAlert.StateChanged)
        assertTrue(out[1] is DeviceAlert.ShotAlert)
        assertTrue(out[2] is DeviceAlert.ErrorAlert)
    }

    @Test
    fun unknownOrEmptyPayloadsAreIgnoredNotThrown() {
        assertTrue(AlertRouter.route(R10Protos.WrapperProto.getDefaultInstance()).single() is DeviceAlert.Ignored)
        val noNotification = R10Protos.WrapperProto.newBuilder()
            .setEvent(R10Protos.EventSharing.newBuilder().build()).build()
        assertTrue(AlertRouter.route(noNotification).single() is DeviceAlert.Ignored)
        val noDetails = R10Protos.WrapperProto.newBuilder()
            .setEvent(
                R10Protos.EventSharing.newBuilder().setNotification(
                    R10Protos.AlertNotification.newBuilder().setType(R10Protos.AlertNotification.AlertType.ACTIVITY_STOP),
                ),
            ).build()
        assertTrue(AlertRouter.route(noDetails).single() is DeviceAlert.Ignored)
        assertTrue(AlertRouter.route(wrap(R10Protos.AlertDetails.newBuilder().build())).single() is DeviceAlert.Ignored)
    }

    @Test
    fun subscribeWrapperTargetsLaunchMonitorAlertType() {
        val w = AlertRouter.launchMonitorSubscribeWrapper()
        assertTrue(w.hasEvent())
        val req = w.event.subscribeRequest
        assertEquals(1, req.alertsCount)
        assertEquals(R10Protos.AlertNotification.AlertType.LAUNCH_MONITOR, req.getAlerts(0).type)
    }
}

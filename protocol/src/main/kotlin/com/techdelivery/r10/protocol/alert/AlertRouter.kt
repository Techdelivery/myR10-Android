package com.techdelivery.r10.protocol.alert

import LaunchMonitor.Proto.R10Protos
import com.techdelivery.r10.protocol.shot.MetricConverter
import com.techdelivery.r10.protocol.shot.Shot

/**
 * A device-pushed alert, decoded from `EventSharing.notification` -> `AlertDetails`
 * (DESIGN §6 / §7.2). One inbound B313 frame can carry more than one kind of
 * payload in the same `AlertDetails`, so [AlertRouter.route] returns a list.
 */
sealed interface DeviceAlert {
    /** §7.2 state changes: STANDBY / WAITING / RECORDING / PROCESSING / ERROR / INTERFERENCE_TEST. */
    data class StateChanged(val state: R10Protos.State.StateType) : DeviceAlert

    /**
     * §7.2 shot data. NOT yet deduplicated — the pump owns that (DESIGN §7.2).
     *
     * [hasDeviceShotId] records whether the frame actually carried `shot_id`.
     * `Metrics.shot_id` is `optional uint32`, so an absent field reads back as `0`
     * and would make every id-less shot look like the same duplicate. The pump uses
     * this flag to avoid dropping real shots; presence is a wire-level fact, so it
     * lives here rather than on the persisted [Shot].
     */
    data class ShotAlert(val shot: Shot, val hasDeviceShotId: Boolean = true) : DeviceAlert

    /** §7.2 errors: OVERHEATING / RADAR_SATURATION / PLATFORM_TILTED + severity. */
    data class ErrorAlert(
        val code: R10Protos.Error.ErrorCode,
        val severity: R10Protos.Error.Severity,
        val rollDeg: Float?,
        val pitchDeg: Float?,
    ) : DeviceAlert

    /** §7.2 tilt calibration status/result. */
    data class CalibrationAlert(
        val status: R10Protos.CalibrationStatus.StatusType,
        val result: R10Protos.CalibrationStatus.CalibrationResult?,
    ) : DeviceAlert

    /** Frame carried nothing this layer understands. Logged, never fatal. */
    data class Ignored(val reason: String) : DeviceAlert
}

object AlertRouter {

    /**
     * Decode a B313 [R10Protos.WrapperProto] into zero-or-more [DeviceAlert]s.
     * Order follows the `AlertDetails` field order (state, metrics, error, calibration).
     */
    fun route(wrapper: R10Protos.WrapperProto, nowMs: Long = System.currentTimeMillis()): List<DeviceAlert> {
        if (!wrapper.hasEvent()) return listOf(DeviceAlert.Ignored("no event in wrapper"))
        val event = wrapper.event
        if (!event.hasNotification()) return listOf(DeviceAlert.Ignored("event has no notification"))
        val note = event.notification
        if (!note.hasAlertNotification()) return listOf(DeviceAlert.Ignored("notification has no AlertDetails (field 1001)"))
        val d = note.alertNotification

        val out = ArrayList<DeviceAlert>(4)
        if (d.hasState()) out += DeviceAlert.StateChanged(d.state.state)
        if (d.hasMetrics()) {
            out += DeviceAlert.ShotAlert(
                shot = MetricConverter.shot(d.metrics, nowMs),
                hasDeviceShotId = d.metrics.hasShotId(),
            )
        }
        if (d.hasError()) {
            val e = d.error
            val tilt = if (e.hasDeviceTilt()) e.deviceTilt else null
            out += DeviceAlert.ErrorAlert(
                code = e.code,
                severity = e.severity,
                rollDeg = tilt?.takeIf { it.hasRoll() }?.roll,
                pitchDeg = tilt?.takeIf { it.hasPitch() }?.pitch,
            )
        }
        if (d.hasTiltCalibration()) {
            val c = d.tiltCalibration
            out += DeviceAlert.CalibrationAlert(
                status = c.status,
                result = c.takeIf { it.hasResult() }?.result,
            )
        }
        if (out.isEmpty()) out += DeviceAlert.Ignored("AlertDetails carried no known fields")
        return out
    }

    /** Build the `AlertNotification` wrapper used by §7.1 step 9 (subscribe). */
    fun launchMonitorSubscribeWrapper(): R10Protos.WrapperProto =
        R10Protos.WrapperProto.newBuilder()
            .setEvent(
                R10Protos.EventSharing.newBuilder().setSubscribeRequest(
                    R10Protos.SubscribeRequest.newBuilder().addAlerts(
                        R10Protos.AlertMessage.newBuilder()
                            .setType(R10Protos.AlertNotification.AlertType.LAUNCH_MONITOR),
                    ),
                ),
            )
            .build()
}

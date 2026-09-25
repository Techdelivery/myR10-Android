package com.techdelivery.r10.state

import LaunchMonitor.Proto.R10Protos
import com.techdelivery.r10.protocol.alert.DeviceAlert
import com.techdelivery.r10.protocol.shot.Shot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * M2 — the §7.2 alert -> UI-state mapping (DESIGN §7.2 / §9).
 */
class AlertMirrorTest {

    @Before
    fun setUp() = DeviceStateHolder.reset()

    @Test
    fun stateChangedSetsStateTypeAndClearsError() {
        DeviceStateHolder.activeError.value = DeviceAlert.ErrorAlert(
            R10Protos.Error.ErrorCode.PLATFORM_TILTED,
            R10Protos.Error.Severity.SERIOUS,
            null,
            null,
        )
        AlertMirror.apply(DeviceAlert.StateChanged(R10Protos.State.StateType.WAITING))
        assertEquals("WAITING", DeviceStateHolder.stateType.value)
        assertNull(DeviceStateHolder.activeError.value)
    }

    @Test
    fun errorStateKeepsTheSurfacedErrorVisible() {
        val err = DeviceAlert.ErrorAlert(
            R10Protos.Error.ErrorCode.OVERHEATING,
            R10Protos.Error.Severity.FATAL,
            3.5f,
            -2.0f,
        )
        AlertMirror.apply(err)
        assertEquals(err, DeviceStateHolder.activeError.value)
        AlertMirror.apply(DeviceAlert.StateChanged(R10Protos.State.StateType.ERROR))
        assertEquals("ERROR", DeviceStateHolder.stateType.value)
        assertEquals(err, DeviceStateHolder.activeError.value)
    }

    @Test
    fun calibrationIsSurfacedAndLatestWins() {
        val first = DeviceAlert.CalibrationAlert(R10Protos.CalibrationStatus.StatusType.IN_BOUNDS, null)
        val second = DeviceAlert.CalibrationAlert(
            R10Protos.CalibrationStatus.StatusType.RECALIBRATION_REQUIRED,
            R10Protos.CalibrationStatus.CalibrationResult.UNIT_MOVING,
        )
        AlertMirror.apply(first)
        assertEquals(R10Protos.CalibrationStatus.StatusType.IN_BOUNDS, DeviceStateHolder.calibration.value?.status)
        AlertMirror.apply(second)
        assertEquals(
            R10Protos.CalibrationStatus.CalibrationResult.UNIT_MOVING,
            DeviceStateHolder.calibration.value?.result,
        )
    }

    @Test
    fun shotAndIgnoredAlertsDoNotTouchConnectionState() {
        val shot = Shot(
            shotId = 3,
            shotType = R10Protos.Metrics.ShotType.PRACTICE,
            receivedAtMs = 1L,
        )
        AlertMirror.apply(DeviceAlert.ShotAlert(shot))
        AlertMirror.apply(DeviceAlert.Ignored("nothing here"))
        assertEquals(0, DeviceStateHolder.shotCount.value) // shots arrive via R10Device.shots, not the mirror
        assertNull(DeviceStateHolder.activeError.value)
        assertNull(DeviceStateHolder.stateType.value)
    }
}

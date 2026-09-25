package com.techdelivery.r10.state

import LaunchMonitor.Proto.R10Protos
import com.techdelivery.r10.protocol.alert.DeviceAlert
import com.techdelivery.r10.protocol.shot.Shot
import com.techdelivery.r10.protocol.shot.SwingDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * M2 — the UI-facing shot/error state the foreground service mirrors into.
 */
class DeviceStateHolderTest {

    private fun shot(id: Int) = Shot(
        shotId = id,
        shotType = R10Protos.Metrics.ShotType.NORMAL,
        receivedAtMs = 1_000L + id,
        swing = SwingDisplay(1L, 2L, 3L, 4L, 5L, tempo = 1.0),
    )

    @Before
    fun setUp() = DeviceStateHolder.reset()

    @Test
    fun shotsAreStoredNewestFirst() {
        DeviceStateHolder.addShot(shot(1))
        DeviceStateHolder.addShot(shot(2))
        DeviceStateHolder.addShot(shot(3))
        assertEquals(listOf(3, 2, 1), DeviceStateHolder.shots.value.map { it.shotId })
        assertEquals(3, DeviceStateHolder.shotCount.value)
    }

    @Test
    fun liveListIsCappedButCountIsNot() {
        repeat(MAX_LIVE_SHOTS + 25) { DeviceStateHolder.addShot(shot(it)) }
        assertEquals(MAX_LIVE_SHOTS, DeviceStateHolder.shots.value.size)
        assertEquals(MAX_LIVE_SHOTS + 25, DeviceStateHolder.shotCount.value)
        // newest still at the head
        assertEquals(MAX_LIVE_SHOTS + 24, DeviceStateHolder.shots.value.first().shotId)
    }

    @Test
    fun healthyStateClearsActiveError() {
        val err = DeviceAlert.ErrorAlert(
            R10Protos.Error.ErrorCode.OVERHEATING,
            R10Protos.Error.Severity.WARNING,
            null,
            null,
        )
        DeviceStateHolder.activeError.value = err
        DeviceStateHolder.onHealthyState("WAITING")
        assertNull(DeviceStateHolder.activeError.value)
    }

    @Test
    fun errorStateKeepsActiveError() {
        val err = DeviceAlert.ErrorAlert(
            R10Protos.Error.ErrorCode.RADAR_SATURATION,
            R10Protos.Error.Severity.FATAL,
            1.0f,
            2.0f,
        )
        DeviceStateHolder.activeError.value = err
        DeviceStateHolder.onHealthyState("ERROR")
        assertEquals(err, DeviceStateHolder.activeError.value)
    }

    @Test
    fun resetClearsShotsAndAlerts() {
        DeviceStateHolder.addShot(shot(7))
        DeviceStateHolder.calibration.value = DeviceAlert.CalibrationAlert(
            R10Protos.CalibrationStatus.StatusType.IN_BOUNDS,
            R10Protos.CalibrationStatus.CalibrationResult.SUCCESS,
        )
        DeviceStateHolder.reset()
        assertEquals(emptyList<Shot>(), DeviceStateHolder.shots.value)
        assertEquals(0, DeviceStateHolder.shotCount.value)
        assertNull(DeviceStateHolder.calibration.value)
        assertNull(DeviceStateHolder.activeError.value)
    }
}

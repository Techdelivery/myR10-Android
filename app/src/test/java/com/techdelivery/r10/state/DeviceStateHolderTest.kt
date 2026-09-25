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

    /**
     * W5 — the service resets on every Start. Persisted CSV history is not
     * connection state, so `resetConnection` must leave the shot list alone while
     * clearing everything that is genuinely per-connection.
     */
    @Test
    fun resetConnectionPreservesShotHistory() {
        DeviceStateHolder.addShot(shot(9))
        DeviceStateHolder.activeError.value = DeviceAlert.ErrorAlert(
            R10Protos.Error.ErrorCode.OVERHEATING,
            R10Protos.Error.Severity.FATAL,
            null,
            null,
        )
        DeviceStateHolder.stateType.value = "ERROR"

        DeviceStateHolder.resetConnection()

        assertEquals(listOf(9), DeviceStateHolder.shots.value.map { it.shotId })
        assertEquals(1, DeviceStateHolder.shotCount.value)
        assertEquals(ConnState.IDLE, DeviceStateHolder.connectionState.value)
        assertNull(DeviceStateHolder.activeError.value)
        assertNull(DeviceStateHolder.stateType.value)
    }

    /**
     * W4 — the old load path checked emptiness before the suspending file read and
     * then assigned, so a shot that landed mid-read was overwritten. `adoptHistory`
     * merges instead: the live shot keeps its place and the loaded rows go below it.
     */
    @Test
    fun adoptHistoryMergesLiveShotsInsteadOfClobbering() {
        DeviceStateHolder.addShot(shot(50)) // arrived while the file was being read
        DeviceStateHolder.adoptHistory(listOf(shot(1), shot(2))) // oldest-first from disk

        assertEquals(listOf(50, 2, 1), DeviceStateHolder.shots.value.map { it.shotId })
        assertEquals(3, DeviceStateHolder.shotCount.value)
    }

    @Test
    fun adoptHistoryOnEmptyAdoptsAllNewestFirst() {
        DeviceStateHolder.adoptHistory(listOf(shot(1), shot(2), shot(3)))
        assertEquals(listOf(3, 2, 1), DeviceStateHolder.shots.value.map { it.shotId })
        assertEquals(3, DeviceStateHolder.shotCount.value)
    }

    @Test
    fun adoptHistoryIsIdempotent() {
        DeviceStateHolder.adoptHistory(listOf(shot(1), shot(2)))
        val afterFirst = DeviceStateHolder.shots.value
        DeviceStateHolder.adoptHistory(listOf(shot(1), shot(2)))
        assertEquals(afterFirst, DeviceStateHolder.shots.value)
        assertEquals(2, DeviceStateHolder.shotCount.value)
    }

    @Test
    fun adoptHistoryRespectsTheLiveCap() {
        DeviceStateHolder.adoptHistory((1..MAX_LIVE_SHOTS + 50).map { shot(it) })
        assertEquals(MAX_LIVE_SHOTS, DeviceStateHolder.shots.value.size)
        assertEquals(MAX_LIVE_SHOTS + 50, DeviceStateHolder.shotCount.value)
    }

    /**
     * A history-write failure has its own channel because `DeviceScreen` only
     * renders `errorMessage` while `conn == ERROR`, and a persist backlog happens
     * while the link is healthy.
     */
    @Test
    fun historyErrorIsClearedByResetConnection() {
        DeviceStateHolder.historyError.value = "queue full"
        DeviceStateHolder.resetConnection()
        assertNull(DeviceStateHolder.historyError.value)
    }

    /**
     * History load and service start race each other on a cold launch. Either order
     * must end with the same merged list and no duplicated rows.
     */
    @Test
    fun adoptHistoryIsStableAcrossAConnectionResetInEitherOrder() {
        // History loaded first, then the service starts.
        DeviceStateHolder.adoptHistory(listOf(shot(1), shot(2)))
        DeviceStateHolder.resetConnection()
        assertEquals(listOf(2, 1), DeviceStateHolder.shots.value.map { it.shotId })
        assertEquals(2, DeviceStateHolder.shotCount.value)

        // Live shots arrive, then a second load sees them already present.
        DeviceStateHolder.addShot(shot(3))
        DeviceStateHolder.adoptHistory(listOf(shot(1), shot(2), shot(3)))
        assertEquals(listOf(3, 2, 1), DeviceStateHolder.shots.value.map { it.shotId })
        assertEquals(3, DeviceStateHolder.shotCount.value)
    }
}

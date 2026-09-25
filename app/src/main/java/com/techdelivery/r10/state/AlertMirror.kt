package com.techdelivery.r10.state

import com.techdelivery.r10.protocol.alert.DeviceAlert

/**
 * Maps a decoded §7.2 device alert onto the UI-facing state.
 *
 * Kept out of the Service so the mapping is unit-testable without Android.
 */
object AlertMirror {
    fun apply(alert: DeviceAlert) {
        when (alert) {
            is DeviceAlert.StateChanged -> {
                DeviceStateHolder.stateType.value = alert.state.name
                DeviceStateHolder.onHealthyState(alert.state.name)
            }
            is DeviceAlert.ErrorAlert -> DeviceStateHolder.activeError.value = alert
            is DeviceAlert.CalibrationAlert -> DeviceStateHolder.calibration.value = alert
            is DeviceAlert.ShotAlert -> Unit // shots go through R10Device.shots (already deduped)
            is DeviceAlert.Ignored -> Unit
        }
    }
}

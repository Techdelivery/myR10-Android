package com.techdelivery.r10.state

import com.techdelivery.r10.protocol.DeviceInfo
import com.techdelivery.r10.protocol.alert.DeviceAlert
import com.techdelivery.r10.protocol.shot.Shot
import com.techdelivery.r10.protocol.util.HexEntry
import com.techdelivery.r10.protocol.util.HexLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

enum class ConnState { IDLE, SCANNING, CONNECTING, HANDSHAKE, READY, ERROR }

/** Cap on in-memory shots so a long range session cannot grow the UI list unbounded. */
const val MAX_LIVE_SHOTS = 200

/**
 * Process-wide connection state. The foreground service owns the device graph and
 * writes here; the Compose UI observes it. Manual-DI singleton (DESIGN §2 — no
 * Hilt), so the UI can read service state without binding.
 */
object DeviceStateHolder {
    val connectionState = kotlinx.coroutines.flow.MutableStateFlow(ConnState.IDLE)
    val deviceInfo = kotlinx.coroutines.flow.MutableStateFlow(DeviceInfo())
    val wakeUpStatus = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val stateType = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val tilt = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val errorMessage = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Live shots, newest first (DESIGN §9 Shots tab). Dedup already applied upstream. */
    val shots = MutableStateFlow<List<Shot>>(emptyList())

    /** Latest §7.2 error alert; cleared when the device returns to a healthy state. */
    val activeError = MutableStateFlow<DeviceAlert.ErrorAlert?>(null)

    /** Latest §7.2 tilt-calibration status. */
    val calibration = MutableStateFlow<DeviceAlert.CalibrationAlert?>(null)

    /** Total shots accepted this session (before the display cap). */
    val shotCount = MutableStateFlow(0)

    /** Shared with the ProtocolEngine so the hex pane sees live TX/RX. */
    val hexLog = HexLog()

    /** Live stream of every TX/RX entry (mirrors hexLog) for logcat capture. */
    val hexFlow = MutableSharedFlow<HexEntry>(extraBufferCapacity = 512)

    init {
        hexLog.listener = { hexFlow.tryEmit(it) }
    }

    fun reset() {
        connectionState.value = ConnState.IDLE
        deviceInfo.value = DeviceInfo()
        wakeUpStatus.value = null
        stateType.value = null
        tilt.value = null
        errorMessage.value = null
        shots.value = emptyList()
        activeError.value = null
        calibration.value = null
        shotCount.value = 0
        hexLog.clear()
    }

    /** Prepend a shot, newest first, capped at [MAX_LIVE_SHOTS]. */
    fun addShot(shot: Shot) {
        shotCount.value = shotCount.value + 1
        shots.value = (listOf(shot) + shots.value).take(MAX_LIVE_SHOTS)
    }

    /** A healthy state clears a previously surfaced error (DESIGN §7.2). */
    fun onHealthyState(stateName: String) {
        if (stateName != "ERROR") activeError.value = null
    }
}

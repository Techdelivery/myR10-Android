package com.techdelivery.r10.state

import com.techdelivery.r10.protocol.DeviceInfo
import com.techdelivery.r10.protocol.util.HexLog

enum class ConnState { IDLE, SCANNING, CONNECTING, HANDSHAKE, READY, ERROR }

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

    /** Shared with the ProtocolEngine so the hex pane sees live TX/RX. */
    val hexLog = HexLog()

    fun reset() {
        connectionState.value = ConnState.IDLE
        deviceInfo.value = DeviceInfo()
        wakeUpStatus.value = null
        stateType.value = null
        tilt.value = null
        errorMessage.value = null
        hexLog.clear()
    }
}

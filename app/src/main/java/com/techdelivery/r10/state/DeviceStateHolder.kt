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

    /**
     * Shot-history write failure. Kept separate from [errorMessage] because
     * `DeviceScreen` only renders that while `conn == ERROR`; a persistence
     * backlog happens while the link is healthy, so it needs its own channel to be
     * visible at all.
     */
    val historyError = MutableStateFlow<String?>(null)

    /** Total shots accepted this session (before the display cap). */
    val shotCount = MutableStateFlow(0)

    /** Shared with the ProtocolEngine so the hex pane sees live TX/RX. */
    val hexLog = HexLog()

    /** Live stream of every TX/RX entry (mirrors hexLog) for logcat capture. */
    val hexFlow = MutableSharedFlow<HexEntry>(extraBufferCapacity = 512)

    init {
        hexLog.listener = { hexFlow.tryEmit(it) }
    }

    /**
     * Guards every write to [shots] / [shotCount]. They have two concurrent writers
     * — the service collector and the UI's history load — so a plain read-modify-write
     * loses updates. A monitor, not a Mutex, because both ops are in-memory and must
     * not become suspendable for their callers.
     */
    private val shotsLock = Any()

    /**
     * Clear connection-scoped state. Preserves [shots] and [shotCount]: persisted
     * history (DESIGN §8) is not connection state, and wiping it here made every
     * Start tap erase the CSV history the UI had just loaded.
     */
    fun resetConnection() = synchronized(shotsLock) {
        connectionState.value = ConnState.IDLE
        deviceInfo.value = DeviceInfo()
        wakeUpStatus.value = null
        stateType.value = null
        tilt.value = null
        errorMessage.value = null
        activeError.value = null
        calibration.value = null
        historyError.value = null
        hexLog.clear()
    }

    /** Full clear, including shot history. For tests and explicit user-initiated wipes. */
    fun reset() = synchronized(shotsLock) {
        resetConnection()
        shots.value = emptyList()
        shotCount.value = 0
    }

    /** Prepend a shot, newest first, capped at [MAX_LIVE_SHOTS]. */
    fun addShot(shot: Shot) = synchronized(shotsLock) {
        shotCount.value = shotCount.value + 1
        shots.value = (listOf(shot) + shots.value).take(MAX_LIVE_SHOTS)
    }

    /**
     * Adopt persisted history into the live list without clobbering live shots.
     *
     * [loaded] is oldest-first, as stored on disk. The old code checked emptiness
     * *before* the suspending file read and then assigned, so a shot that arrived
     * during the read was overwritten and lost from the UI. Merging under [shotsLock]
     * closes that window.
     */
    fun adoptHistory(loaded: List<Shot>) = synchronized(shotsLock) {
        if (loaded.isEmpty()) return@synchronized
        val current = shots.value
        if (current.isEmpty()) {
            shots.value = loaded.asReversed().take(MAX_LIVE_SHOTS)
            shotCount.value = loaded.size
        } else {
            val known = current.mapTo(mutableSetOf()) { it.shotId to it.receivedAtMs }
            // Loaded rows are older than anything already live, so they go below.
            val extra = loaded.asReversed().filter { (it.shotId to it.receivedAtMs) !in known }
            if (extra.isEmpty()) return@synchronized
            shots.value = (current + extra).take(MAX_LIVE_SHOTS)
            shotCount.value = shotCount.value + extra.size
        }
    }

    /** A healthy state clears a previously surfaced error (DESIGN §7.2). */
    fun onHealthyState(stateName: String) {
        if (stateName != "ERROR") activeError.value = null
    }
}

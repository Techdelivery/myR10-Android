package com.techdelivery.r10.state

import com.techdelivery.r10.protocol.DeviceInfo
import com.techdelivery.r10.protocol.alert.DeviceAlert
import com.techdelivery.r10.protocol.shot.Shot
import com.techdelivery.r10.protocol.util.HexEntry
import com.techdelivery.r10.protocol.util.HexLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

enum class ConnState { IDLE, SCANNING, CONNECTING, HANDSHAKE, READY, ERROR }

/**
 * Device tilt in degrees, as the R10 last reported it.
 *
 * Typed because the formatted [DeviceStateHolder.tilt] string is fine for the
 * Device tab and useless for logic — the Shots tab needs the numbers to warn about
 * a bad pitch (ROADMAP R2). The values are the device's own; the app deliberately
 * does not invent its own level/unlevel threshold, because a locally guessed one can
 * disagree with the R10's and be worse than nothing.
 */
data class TiltReading(val rollDeg: Float, val pitchDeg: Float)

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

    /** Typed counterpart of [tilt]: the latest roll/pitch the device reported. */
    val tiltReading = MutableStateFlow<TiltReading?>(null)
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
        tiltReading.value = null
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
     * Re-tag a shot already in the live list (ROADMAP R5).
     *
     * The store is the source of truth; this mirrors the write so the Shots tab
     * shows the new club without a reload. A club is not part of the shot's
     * identity, so the row is replaced in place and its position is preserved.
     *
     * Matched on the same **pair** the store matches on — id *and* arrival time.
     * The R10 restarts `shot_id` on every power cycle, so the live list can hold
     * yesterday's shot 1 and today's shot 1, and tagging by id alone would
     * overwrite both.
     */
    fun setShotClub(shotId: Int, receivedAtMs: Long, clubLabel: String?) = synchronized(shotsLock) {
        shots.value = shots.value.map { shot ->
            if (shot.shotId == shotId && shot.receivedAtMs == receivedAtMs) {
                shot.copy(clubLabel = clubLabel)
            } else {
                shot
            }
        }
    }

    /**
     * Drop a deleted shot from the live list (ROADMAP R6).
     *
     * Matched on the same **pair** the store deletes on — id *and* arrival time —
     * for the same reason: two sessions can each hold a shot 1, and dropping by id
     * alone would remove both rows from the list.
     *
     * [shotCount] is deliberately **not** decremented: it is a session counter of
     * shots the device sent, and the notification shows it as such. Decrementing
     * would make the count disagree with the R10's own record.
     */
    fun removeShot(shotId: Int, receivedAtMs: Long) = synchronized(shotsLock) {
        shots.value = shots.value.filter { it.shotId != shotId || it.receivedAtMs != receivedAtMs }
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

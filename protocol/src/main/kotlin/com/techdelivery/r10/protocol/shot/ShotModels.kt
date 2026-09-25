package com.techdelivery.r10.protocol.shot

import LaunchMonitor.Proto.R10Protos

/**
 * Display-ready shot values (DESIGN §7.3). Raw proto units are m/s; these are
 * converted for humans. Angles stay degrees, spin stays rpm, timings stay
 * microseconds.
 */
data class BallDisplay(
    val ballSpeedMph: Double,
    val launchAngleDeg: Double,
    /** Horizontal launch angle (DESIGN §7.3 "launchDir"). */
    val launchDirectionDeg: Double,
    /** Already negated (DESIGN §7.3: spin_axis x -1). */
    val spinAxisDeg: Double,
    val totalSpinRpm: Double,
    val sideSpinRpm: Double,
    val backSpinRpm: Double,
)

data class ClubDisplay(
    val clubSpeedMph: Double,
    val faceAngleDeg: Double,
    val pathDeg: Double,
    val attackAngleDeg: Double,
)

data class SwingDisplay(
    /** All timings microseconds, as reported by the device. */
    val backswingStartUs: Long,
    val downswingStartUs: Long,
    val impactUs: Long,
    val followThroughEndUs: Long,
    val endRecordingUs: Long,
    /** backswingDuration / downswingDuration; null when downswing duration is 0. */
    val tempo: Double?,
) {
    val backswingDurationUs: Long get() = impactUs - backswingStartUs
    val downswingDurationUs: Long get() = impactUs - downswingStartUs
}

/**
 * One deduplicated shot (DESIGN §8 `Shot` entity, pre-persistence form).
 * [rawMetrics] keeps the original proto bytes so a future schema change can
 * re-parse stored shots instead of losing them.
 */
data class Shot(
    val shotId: Int,
    /** Proto enum passed through unchanged (PRACTICE / NORMAL). */
    val shotType: R10Protos.Metrics.ShotType,
    val receivedAtMs: Long,
    val ball: BallDisplay? = null,
    val club: ClubDisplay? = null,
    val swing: SwingDisplay? = null,
    val rawMetrics: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Shot) return false
        return shotId == other.shotId &&
            shotType == other.shotType &&
            receivedAtMs == other.receivedAtMs &&
            ball == other.ball &&
            club == other.club &&
            swing == other.swing &&
            rawMetrics.contentEquals(other.rawMetrics)
    }

    override fun hashCode(): Int {
        var r = shotId
        r = 31 * r + shotType.hashCode()
        r = 31 * r + receivedAtMs.hashCode()
        r = 31 * r + (ball?.hashCode() ?: 0)
        r = 31 * r + (club?.hashCode() ?: 0)
        r = 31 * r + (swing?.hashCode() ?: 0)
        r = 31 * r + rawMetrics.contentHashCode()
        return r
    }
}

package com.techdelivery.r10.protocol.shot

import LaunchMonitor.Proto.R10Protos
import kotlin.math.cos
import kotlin.math.sin

/**
 * DESIGN §7.3 metric conversion. Pure functions — every formula here is a
 * verbatim transcription of the documented conversion table.
 */
object MetricConverter {

    /** m/s -> mph (DESIGN §7.3). */
    const val MPS_TO_MPH = 2.2369

    fun toMph(metersPerSecond: Float): Double = metersPerSecond.toDouble() * MPS_TO_MPH

    fun ball(m: R10Protos.BallMetrics): BallDisplay {
        val totalSpin = m.totalSpin.toDouble()
        // DESIGN §7.3: spinAxis = spin_axis * -1, and side/back spin are computed
        // from the NEGATED axis (sin/cos of -spin_axis in radians).
        val negatedAxis = -1.0 * m.spinAxis.toDouble()
        val axisRad = negatedAxis * Math.PI / 180.0
        return BallDisplay(
            ballSpeedMph = toMph(m.ballSpeed),
            launchAngleDeg = m.launchAngle.toDouble(),
            launchDirectionDeg = m.launchDirection.toDouble(),
            spinAxisDeg = negatedAxis,
            totalSpinRpm = totalSpin,
            sideSpinRpm = totalSpin * sin(axisRad),
            backSpinRpm = totalSpin * cos(axisRad),
        )
    }

    fun club(m: R10Protos.ClubMetrics): ClubDisplay = ClubDisplay(
        clubSpeedMph = toMph(m.clubHeadSpeed),
        faceAngleDeg = m.clubAngleFace.toDouble(),
        pathDeg = m.clubAnglePath.toDouble(),
        attackAngleDeg = m.attackAngle.toDouble(),
    )

    fun swing(m: R10Protos.SwingMetrics): SwingDisplay {
        val backStart = m.backSwingStartTime.toLong() and 0xFFFFFFFFL
        val downStart = m.downSwingStartTime.toLong() and 0xFFFFFFFFL
        val impact = m.impactTime.toLong() and 0xFFFFFFFFL
        val followEnd = m.followThroughEndTime.toLong() and 0xFFFFFFFFL
        val endRec = m.endRecordingTime.toLong() and 0xFFFFFFFFL
        val backswingDuration = impact - backStart
        val downswingDuration = impact - downStart
        val tempo = if (downswingDuration == 0L) null else backswingDuration.toDouble() / downswingDuration.toDouble()
        return SwingDisplay(
            backswingStartUs = backStart,
            downswingStartUs = downStart,
            impactUs = impact,
            followThroughEndUs = followEnd,
            endRecordingUs = endRec,
            tempo = tempo,
        )
    }

    /** Full `Metrics` -> display [Shot] (DESIGN §7.3 + §8 rawMetrics). */
    fun shot(m: R10Protos.Metrics, receivedAtMs: Long): Shot = Shot(
        shotId = m.shotId,
        shotType = m.shotType,
        receivedAtMs = receivedAtMs,
        ball = if (m.hasBallMetrics()) ball(m.ballMetrics) else null,
        club = if (m.hasClubMetrics()) club(m.clubMetrics) else null,
        swing = if (m.hasSwingMetrics()) swing(m.swingMetrics) else null,
        rawMetrics = m.toByteArray(),
    )
}

package com.techdelivery.r10.protocol.shot

import LaunchMonitor.Proto.R10Protos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M2 — DESIGN §7.3 conversion table pinned formula-by-formula.
 */
class MetricConverterTest {

    private fun approx(expected: Double, actual: Double, eps: Double = 1e-6) =
        assertEquals(expected, actual, eps)

    @Test
    fun speedConversionUsesDocumentedFactor() {
        approx(111.845, MetricConverter.toMph(50f))
        approx(0.0, MetricConverter.toMph(0f))
        assertEquals(2.2369, MetricConverter.MPS_TO_MPH, 0.0)
    }

    @Test
    fun ballSpinIsComputedFromNegatedAxis() {
        val m = R10Protos.BallMetrics.newBuilder()
            .setBallSpeed(50f)      // m/s
            .setLaunchAngle(12.5f)
            .setLaunchDirection(-3.0f)
            .setSpinAxis(90f)
            .setTotalSpin(3000f)    // rpm
            .build()
        val b = MetricConverter.ball(m)
        approx(111.845, b.ballSpeedMph)
        approx(12.5, b.launchAngleDeg)
        approx(-3.0, b.launchDirectionDeg)
        approx(-90.0, b.spinAxisDeg)          // spin_axis * -1
        approx(3000.0, b.totalSpinRpm)
        approx(-3000.0, b.sideSpinRpm, 1e-3) // 3000 * sin(-90deg)
        approx(0.0, b.backSpinRpm, 1e-3)     // 3000 * cos(-90deg)
    }

    @Test
    fun pureBackSpinWhenAxisZero() {
        val m = R10Protos.BallMetrics.newBuilder()
            .setSpinAxis(0f).setTotalSpin(2800f).build()
        val b = MetricConverter.ball(m)
        approx(0.0, b.sideSpinRpm, 1e-9)
        approx(2800.0, b.backSpinRpm, 1e-9)
    }

    @Test
    fun clubFieldsMapDirectlyAndSpeedConverts() {
        val m = R10Protos.ClubMetrics.newBuilder()
            .setClubHeadSpeed(40f)
            .setClubAngleFace(2.5f)
            .setClubAnglePath(-1.5f)
            .setAttackAngle(3.25f)
            .build()
        val c = MetricConverter.club(m)
        approx(89.476, c.clubSpeedMph, 1e-3)
        approx(2.5, c.faceAngleDeg)
        approx(-1.5, c.pathDeg)
        approx(3.25, c.attackAngleDeg)
    }

    @Test
    fun swingTempoIsBackswingOverDownswingDuration() {
        val m = R10Protos.SwingMetrics.newBuilder()
            .setBackSwingStartTime(1_000)
            .setDownSwingStartTime(1_900)
            .setImpactTime(2_000)
            .setFollowThroughEndTime(3_000)
            .setEndRecordingTime(3_500)
            .build()
        val s = MetricConverter.swing(m)
        assertEquals(1_000L, s.backswingDurationUs)   // impact - backswingStart
        assertEquals(100L, s.downswingDurationUs)     // impact - downswingStart
        approx(10.0, s.tempo!!)
        assertEquals(3_500L, s.endRecordingUs)
    }

    @Test
    fun zeroDownswingDurationYieldsNullTempo() {
        val m = R10Protos.SwingMetrics.newBuilder()
            .setBackSwingStartTime(1_000)
            .setDownSwingStartTime(2_000) // == impact time -> zero downswing duration
            .setImpactTime(2_000)
            .build()
        val s = MetricConverter.swing(m)
        assertEquals(0L, s.downswingDurationUs)
        assertNull(s.tempo)
    }

    @Test
    fun shotCarriesSubMessagesAndRawProtoBytes() {
        val metrics = R10Protos.Metrics.newBuilder()
            .setShotId(42)
            .setShotType(R10Protos.Metrics.ShotType.PRACTICE)
            .setBallMetrics(R10Protos.BallMetrics.newBuilder().setBallSpeed(60f))
            .build()
        val shot = MetricConverter.shot(metrics, receivedAtMs = 1234L)
        assertEquals(42, shot.shotId)
        assertEquals(R10Protos.Metrics.ShotType.PRACTICE, shot.shotType)
        assertEquals(1234L, shot.receivedAtMs)
        assertEquals(134.214, shot.ball!!.ballSpeedMph, 1e-3)
        assertNull(shot.club)
        assertNull(shot.swing)
        // rawMetrics must re-parse to the original message (DESIGN §8 re-parse support)
        val reparsed = R10Protos.Metrics.parseFrom(shot.rawMetrics)
        assertEquals(metrics, reparsed)
        assertTrue(shot.rawMetrics.isNotEmpty())
    }
}

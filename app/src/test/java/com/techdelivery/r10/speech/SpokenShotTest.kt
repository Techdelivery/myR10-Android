package com.techdelivery.r10.speech

import LaunchMonitor.Proto.R10Protos
import com.techdelivery.r10.protocol.shot.BallDisplay
import com.techdelivery.r10.protocol.shot.MetricConverter
import com.techdelivery.r10.protocol.shot.Shot
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the phone says (ROADMAP R10).
 *
 * The whole point of the feature is that it says one number and nothing else, so
 * these tests are mostly about the cases where it must say *nothing* — an empty
 * phrase is a real answer, and speaking a zero would be a lie the user hears at the
 * worst possible moment.
 */
class SpokenShotTest {

    private fun shot(mph: Double?): Shot {
        val metrics = R10Protos.Metrics.newBuilder().setShotId(1)
        if (mph != null) {
            metrics.setBallMetrics(
                R10Protos.BallMetrics.newBuilder()
                    .setBallSpeed((mph / 2.2369).toFloat()) // the converter's m/s -> mph
                    .build(),
            )
        }
        return MetricConverter.shot(metrics.build(), 0L)
    }

    @Test
    fun aNormalSpeedIsTheWholeNumber() {
        assertEquals("155", SpokenShot.speed(shot(155.0)))
    }

    @Test
    fun speedIsRoundedNotTruncated() {
        // 154.6 is a 155 to a golfer; truncation would read low on every shot that
        // is not exact, which reads as a device that cannot count. And 154.4 really
        // is a 154 — the test that catches a floor-instead-of-round bug.
        assertEquals("155", SpokenShot.speed(shot(154.6)))
        assertEquals("154", SpokenShot.speed(shot(154.4)))
    }

    @Test
    fun aThreeDigitSpeedSurvives() {
        assertEquals("300", SpokenShot.speed(shot(299.6)))
    }

    @Test
    fun aShotWithNoBallMetricsSaysNothing() {
        assertEquals("", SpokenShot.speed(shot(null)))
    }

    @Test
    fun aNaNSpeedSaysNothing() {
        assertEquals("", SpokenShot.speed(shot(Double.NaN)))
    }

    @Test
    fun anImpossibleSpeedSaysNothingRatherThanSomethingAbsurd() {
        // A broken reading is not a number to announce. A zero would also be heard as
        // a real, terrible shot.
        assertEquals("", SpokenShot.speed(shot(0.0)))
        assertEquals("", SpokenShot.speed(shot(9999.0)))
    }

    @Test
    fun theClubIsNotSpoken() {
        // Decided 2026-09-28: the number only. If this ever fails, someone has added
        // a word back in, and the gap between shots got longer.
        val tagged = shot(150.0).copy(clubLabel = "7I")
        assertEquals("150", SpokenShot.speed(tagged))
    }

    @Test
    fun speedIsTheBallSpeedNotTheClubSpeed() {
        val both = Shot(
            shotId = 1,
            shotType = R10Protos.Metrics.ShotType.NORMAL,
            receivedAtMs = 0L,
            ball = BallDisplay(155.0, 12.0, -1.0, -90.0, 7000.0, -3000.0, 3000.0),
            club = com.techdelivery.r10.protocol.shot.ClubDisplay(95.0, 1.0, 1.0, 1.0),
        )
        assertEquals("155", SpokenShot.speed(both))
    }
}

package com.techdelivery.r10.speech

import com.techdelivery.r10.protocol.shot.Shot
import java.util.Locale
import kotlin.math.roundToInt

/**
 * What gets said out loud for an arriving shot (ROADMAP R10).
 *
 * **The number, and nothing else** — decided 2026-09-28. At the range the unit is
 * never in doubt, and the syllables are what make the gap between shots feel long.
 * It also retires a problem: TTS reads "mph" out letter by letter, so there is no
 * abbreviation to argue about any more.
 *
 * Pure and total, on purpose. This is the part worth testing; [ShotSpeaker] is the
 * part that talks to the platform and is not.
 */
object SpokenShot {

    /**
     * The ball speed as a whole number, or **empty** when there is nothing worth
     * saying.
     *
     * Empty is a real answer, not an oversight: a shot with no ball metrics must
     * produce silence. Speaking "0", or a placeholder, is worse than saying nothing,
     * and the caller skips an empty phrase entirely.
     *
     * Rounded, not truncated — 154.6 mph is a 155 to every golfer, and truncation
     * would read a whole unit low on every shot that is not exact.
     */
    fun speed(shot: Shot): String {
        val ball = shot.ball ?: return ""
        if (ball.ballSpeedMph.isNaN()) return ""
        val whole = ball.ballSpeedMph.roundToInt()
        // A physically impossible speed means a broken reading, not a number to
        // announce. Say nothing rather than something absurd.
        if (whole !in PLAUSIBLE_MPH) return ""
        return String.format(Locale.US, "%d", whole)
    }

    /** Ball speeds outside this range are a bad reading, not a shot. */
    private val PLAUSIBLE_MPH = 1..350
}

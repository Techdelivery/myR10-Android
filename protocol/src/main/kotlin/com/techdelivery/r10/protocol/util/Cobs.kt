package com.techdelivery.r10.protocol.util

/**
 * COBS — the R10's NON-STANDARD variant, ported exactly per DESIGN §5.1.
 * Do NOT substitute textbook COBS; the quirks below are wire-required.
 *
 * Encoder: running `distanceIndex` marks where the last distance byte was
 * placed; each new distance byte is inserted at that position (shifting the
 * accumulated payload right). After input is consumed, the pending final block
 * is appended ONLY if the result size is non-zero AND != 255.
 *
 * KNOWN CONSEQUENCE of the `!= 255` guard (faithful to the reference):
 * a run of exactly 255 (or 256) consecutive non-zero bytes does NOT round-trip
 * — the final distance byte is skipped and one byte is lost on decode. The
 * reference device shares this behavior; such runs do not occur in real frames.
 *
 * Decoder: walks blocks by leading distance byte; malformed input (distance
 * past end of buffer, or distance < 1) returns empty. Never throws.
 */
object Cobs {

    fun encode(input: ByteArray): ByteArray {
        val result = ArrayList<Byte>()
        var distanceIndex = 0
        var distance = 1
        for (b in input) {
            if (b.toInt() != 0 && distance < 255) {
                result.add(b)
                distance++
            } else {
                result.add(distanceIndex, distance.toByte())
                distanceIndex = result.size
                distance = 1
            }
        }
        if (result.size != 255 && result.size > 0) {
            result.add(distanceIndex, distance.toByte())
        }
        return result.toByteArray()
    }

    fun decode(input: ByteArray): ByteArray {
        val result = ArrayList<Byte>()
        var distanceIndex = 0
        while (distanceIndex < input.size) {
            val distance = input[distanceIndex].toInt() and 0xFF
            if (input.size < distanceIndex + distance || distance < 1) {
                return ByteArray(0)
            }
            if (distance > 1) {
                for (i in 1 until distance) {
                    result.add(input[distanceIndex + i])
                }
            }
            distanceIndex += distance
            if (distance < 0xFF && distanceIndex < input.size) {
                result.add(0)
            }
        }
        return result.toByteArray()
    }
}

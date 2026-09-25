package com.techdelivery.r10.settings

/**
 * App settings (DESIGN §8). Defaults match the documented DataStore keys.
 */
data class AppSettings(
    val autoWake: Boolean = true,
    val calibrateTiltOnConnect: Boolean = false,
    val temperature: Int = 60,
    val humidity: Int = 1,
    val altitude: Int = 0,
    val airDensity: Double = 1.0,
    val teeDistanceFt: Int = 7,
    val debugLogging: Boolean = false,
    val reconnectIntervalS: Int = 5,
    val deviceName: String = "Approach R10",
) {
    companion object {
        /**
         * Sane envelopes around the DESIGN §8 defaults. Every setter clamps to
         * these, so a runaway stepper cannot push an out-of-range value at the
         * device. `tee_range` in particular is derived from [teeDistanceFt] and is
         * sent as a float — a negative tee distance is meaningless to the R10.
         */
        val TEMPERATURE_F: IntRange = 10..120

        /**
         * DESIGN §8 stores humidity as an `Int` with default 1, while the proto
         * `ShotConfigRequest.humidity` is a float fraction of 1. Only 0 and 1 are
         * representable today; a real 0..100 % model needs a settings migration,
         * not just a wider range here.
         */
        val HUMIDITY: IntRange = 0..1
        val ALTITUDE_M: IntRange = 0..8_000
        val AIR_DENSITY: ClosedFloatingPointRange<Double> = 0.5..1.5
        val TEE_DISTANCE_FT: IntRange = 1..50
        val RECONNECT_INTERVAL_S: IntRange = 1..300

        /**
         * BLE advertises at most 31 bytes of name, and this value is also the scan
         * filter handed to `BleTransportImpl` — an over-long or blank name does not
         * just break the UI, it silently breaks device discovery. Clamped and
         * trimmed on write.
         */
        const val DEVICE_NAME_MAX_LEN = 31
        const val DEVICE_NAME_FALLBACK = "Approach R10"
    }
}

/** Bridge for the Double-based stepper UI. */
internal fun IntRange.asDoubleRange(): ClosedFloatingPointRange<Double> = first().toDouble()..last().toDouble()

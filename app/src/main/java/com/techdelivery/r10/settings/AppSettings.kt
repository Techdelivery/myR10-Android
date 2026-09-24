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
)

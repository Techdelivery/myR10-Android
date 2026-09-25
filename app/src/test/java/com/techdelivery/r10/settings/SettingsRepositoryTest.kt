package com.techdelivery.r10.settings

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class SettingsRepositoryTest {

    private fun newRepo(): SettingsRepository {
        val dir = Files.createTempDirectory("r10-settings").toFile()
        val file = java.io.File(dir, "test.preferences_pb")
        return SettingsRepository(SettingsDataStore.produceStore(file))
    }

    @Test
    fun defaults_whenEmpty() = runBlocking {
        val s = newRepo().settings.first()
        assertEquals(true, s.autoWake)
        assertEquals(false, s.calibrateTiltOnConnect)
        assertEquals(60, s.temperature)
        assertEquals(1, s.humidity)
        assertEquals(0, s.altitude)
        assertEquals(1.0, s.airDensity, 0.0)
        assertEquals(7, s.teeDistanceFt)
        assertEquals(false, s.debugLogging)
        assertEquals(5, s.reconnectIntervalS)
        assertEquals("Approach R10", s.deviceName)
    }

    @Test
    fun editsRoundTrip() = runBlocking {
        val repo = newRepo()
        repo.setAutoWake(false)
        repo.setCalibrateTiltOnConnect(true)
        repo.setTemperature(75)
        repo.setHumidity(0) // HUMIDITY range is 0..1; see clamp test below
        repo.setAltitude(1200)
        repo.setAirDensity(1.15)
        repo.setTeeDistanceFt(10)
        repo.setDebugLogging(true)
        repo.setReconnectIntervalS(8)
        repo.setDeviceName("My R10")

        val s = repo.settings.first()
        assertEquals(false, s.autoWake)
        assertEquals(true, s.calibrateTiltOnConnect)
        assertEquals(75, s.temperature)
        assertEquals(0, s.humidity)
        assertEquals(1200, s.altitude)
        assertEquals(1.15, s.airDensity, 0.0)
        assertEquals(10, s.teeDistanceFt)
        assertEquals(true, s.debugLogging)
        assertEquals(8, s.reconnectIntervalS)
        assertEquals("My R10", s.deviceName)
    }

    /**
     * W7 — every numeric setter clamps to the DESIGN §8 envelope in [AppSettings],
     * so a runaway stepper cannot put an out-of-range value on the wire.
     *
     * Humidity is the awkward one: DESIGN §8 keeps it an `Int` whose default of 1
     * means a fully-saturated fraction, so 40 is clamped to 1 rather than stored.
     * A real 0..100 % model needs a settings migration, not a wider range.
     */
    @Test
    fun numericSettersClampToDesignRanges() = runBlocking {
        val repo = newRepo()
        repo.setTemperature(-500)
        repo.setHumidity(40)
        repo.setAltitude(999_999)
        repo.setAirDensity(0.0)
        repo.setTeeDistanceFt(-3)
        repo.setReconnectIntervalS(0)

        val s = repo.settings.first()
        assertEquals(AppSettings.TEMPERATURE_F.first, s.temperature)
        assertEquals(AppSettings.HUMIDITY.last, s.humidity)
        assertEquals(AppSettings.ALTITUDE_M.last, s.altitude)
        assertEquals(0.5, s.airDensity, 1e-9)
        assertEquals(AppSettings.TEE_DISTANCE_FT.first, s.teeDistanceFt)
        assertEquals(AppSettings.RECONNECT_INTERVAL_S.first, s.reconnectIntervalS)
    }

    @Test
    fun clampsAtTheUpperBoundToo() = runBlocking {
        val repo = newRepo()
        repo.setTemperature(100_000)
        repo.setAirDensity(Double.NaN)
        assertEquals(AppSettings.TEMPERATURE_F.last, repo.settings.first().temperature)
        // NaN would be written to the wire as a meaningless float; it falls back to the default.
        assertEquals(1.0, repo.settings.first().airDensity, 1e-9)
    }

    @Test
    fun inRangeValuesAreStoredUnclamped() = runBlocking {
        val repo = newRepo()
        repo.setTemperature(AppSettings.TEMPERATURE_F.last)
        repo.setTeeDistanceFt(AppSettings.TEE_DISTANCE_FT.first)
        repo.setAirDensity(1.42)
        val s = repo.settings.first()
        assertEquals(120, s.temperature)
        assertEquals(1, s.teeDistanceFt)
        assertEquals(1.42, s.airDensity, 1e-9)
    }

    /**
     * The device name is also the BLE scan filter, so it is trimmed, capped at the
     * advertised-name limit, and never allowed to go blank — a blank filter means
     * the R10 is simply never found.
     */
    @Test
    fun deviceNameIsTrimmedCappedAndNeverBlank() = runBlocking {
        val repo = newRepo()

        repo.setDeviceName("  " + "x".repeat(60) + "  ")
        assertEquals(AppSettings.DEVICE_NAME_MAX_LEN, repo.settings.first().deviceName.length)

        repo.setDeviceName("   \t  ")
        assertEquals(AppSettings.DEVICE_NAME_FALLBACK, repo.settings.first().deviceName)

        repo.setDeviceName("  My R10  ")
        assertEquals("My R10", repo.settings.first().deviceName)
    }
}

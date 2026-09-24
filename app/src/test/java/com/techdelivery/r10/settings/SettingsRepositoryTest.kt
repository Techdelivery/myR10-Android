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
        repo.setHumidity(40)
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
        assertEquals(40, s.humidity)
        assertEquals(1200, s.altitude)
        assertEquals(1.15, s.airDensity, 0.0)
        assertEquals(10, s.teeDistanceFt)
        assertEquals(true, s.debugLogging)
        assertEquals(8, s.reconnectIntervalS)
        assertEquals("My R10", s.deviceName)
    }
}

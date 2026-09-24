package com.techdelivery.r10.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed settings. Takes a [DataStore] so tests can inject a temp-file
 * store without an Android Context. Defaults are applied on read (DESIGN §8).
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = dataStore.data.map { p ->
        AppSettings(
            autoWake = p[KEY_AUTO_WAKE] ?: true,
            calibrateTiltOnConnect = p[KEY_CALIBRATE_TILT] ?: false,
            temperature = p[KEY_TEMPERATURE] ?: 60,
            humidity = p[KEY_HUMIDITY] ?: 1,
            altitude = p[KEY_ALTITUDE] ?: 0,
            airDensity = p[KEY_AIR_DENSITY] ?: 1.0,
            teeDistanceFt = p[KEY_TEE_DISTANCE_FT] ?: 7,
            debugLogging = p[KEY_DEBUG_LOGGING] ?: false,
            reconnectIntervalS = p[KEY_RECONNECT_INTERVAL_S] ?: 5,
            deviceName = p[KEY_DEVICE_NAME] ?: "Approach R10",
        )
    }

    suspend fun setAutoWake(v: Boolean) { dataStore.edit { it[KEY_AUTO_WAKE] = v } }
    suspend fun setCalibrateTiltOnConnect(v: Boolean) { dataStore.edit { it[KEY_CALIBRATE_TILT] = v } }
    suspend fun setTemperature(v: Int) { dataStore.edit { it[KEY_TEMPERATURE] = v } }
    suspend fun setHumidity(v: Int) { dataStore.edit { it[KEY_HUMIDITY] = v } }
    suspend fun setAltitude(v: Int) { dataStore.edit { it[KEY_ALTITUDE] = v } }
    suspend fun setAirDensity(v: Double) { dataStore.edit { it[KEY_AIR_DENSITY] = v } }
    suspend fun setTeeDistanceFt(v: Int) { dataStore.edit { it[KEY_TEE_DISTANCE_FT] = v } }
    suspend fun setDebugLogging(v: Boolean) { dataStore.edit { it[KEY_DEBUG_LOGGING] = v } }
    suspend fun setReconnectIntervalS(v: Int) { dataStore.edit { it[KEY_RECONNECT_INTERVAL_S] = v } }
    suspend fun setDeviceName(v: String) { dataStore.edit { it[KEY_DEVICE_NAME] = v } }

    companion object {
        private val KEY_AUTO_WAKE = booleanPreferencesKey("autoWake")
        private val KEY_CALIBRATE_TILT = booleanPreferencesKey("calibrateTiltOnConnect")
        private val KEY_TEMPERATURE = intPreferencesKey("temperature")
        private val KEY_HUMIDITY = intPreferencesKey("humidity")
        private val KEY_ALTITUDE = intPreferencesKey("altitude")
        private val KEY_AIR_DENSITY = doublePreferencesKey("airDensity")
        private val KEY_TEE_DISTANCE_FT = intPreferencesKey("teeDistanceFt")
        private val KEY_DEBUG_LOGGING = booleanPreferencesKey("debugLogging")
        private val KEY_RECONNECT_INTERVAL_S = intPreferencesKey("reconnectIntervalS")
        private val KEY_DEVICE_NAME = stringPreferencesKey("deviceName")
    }
}

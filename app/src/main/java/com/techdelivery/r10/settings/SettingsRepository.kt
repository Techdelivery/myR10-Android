package com.techdelivery.r10.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.techdelivery.r10.club.GolfClub
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
            ownedClubs = p[KEY_OWNED_CLUBS] ?: AppSettings.DEFAULT_OWNED_CLUBS,
            currentClub = p[KEY_CURRENT_CLUB],
        )
    }

    suspend fun setAutoWake(v: Boolean) {
        dataStore.edit { it[KEY_AUTO_WAKE] = v }
    }
    suspend fun setCalibrateTiltOnConnect(v: Boolean) {
        dataStore.edit { it[KEY_CALIBRATE_TILT] = v }
    }

    // Every numeric setter clamps to the DESIGN §8 envelope in AppSettings. The UI
    // stepper also bounds its buttons, but the repository is the last place that
    // sees the value before it reaches the device, so it enforces too.
    suspend fun setTemperature(v: Int) = putClamped(KEY_TEMPERATURE, v, AppSettings.TEMPERATURE_F)
    suspend fun setHumidity(v: Int) = putClamped(KEY_HUMIDITY, v, AppSettings.HUMIDITY)
    suspend fun setAltitude(v: Int) = putClamped(KEY_ALTITUDE, v, AppSettings.ALTITUDE_M)
    suspend fun setTeeDistanceFt(v: Int) = putClamped(KEY_TEE_DISTANCE_FT, v, AppSettings.TEE_DISTANCE_FT)
    suspend fun setReconnectIntervalS(v: Int) =
        putClamped(KEY_RECONNECT_INTERVAL_S, v, AppSettings.RECONNECT_INTERVAL_S)

    suspend fun setAirDensity(v: Double) {
        val clamped = if (v.isNaN()) 1.0 else v.coerceIn(AppSettings.AIR_DENSITY)
        dataStore.edit { it[KEY_AIR_DENSITY] = clamped }
    }

    private suspend fun putClamped(key: Preferences.Key<Int>, v: Int, range: IntRange) {
        val clamped = v.coerceIn(range)
        dataStore.edit { it[key] = clamped }
    }

    suspend fun setDebugLogging(v: Boolean) {
        dataStore.edit { it[KEY_DEBUG_LOGGING] = v }
    }
    suspend fun setDeviceName(v: String) {
        // Trim, cap at the BLE name limit, and never store blank — this value is
        // the scan filter, so a blank name means the R10 is never found.
        val name = v.trim().take(AppSettings.DEVICE_NAME_MAX_LEN)
            .ifBlank { AppSettings.DEVICE_NAME_FALLBACK }
        dataStore.edit { it[KEY_DEVICE_NAME] = name }
    }

    /**
     * Add or remove one club from the owned bag (ROADMAP R5).
     *
     * Read-modify-write inside `edit` rather than from the exposed flow: two taps
     * in quick succession would otherwise both read the same stale set and the
     * second would drop the first's change.
     *
     * An empty set is preserved, not defaulted back to the full bag — "I own
     * nothing" is a real answer. The picker handles the fallback.
     */
    suspend fun setClubOwned(id: String, owned: Boolean) {
        if (GolfClub.fromId(id) == null) return
        dataStore.edit { p ->
            val current = p[KEY_OWNED_CLUBS] ?: AppSettings.DEFAULT_OWNED_CLUBS
            p[KEY_OWNED_CLUBS] = if (owned) current + id else current - id
        }
    }

    /**
     * Remember the last club the user picked, for the arrival stamp.
     *
     * Validated against the canonical list: this value ends up in the CSV, and a
     * typo here would be written onto every subsequent shot.
     */
    suspend fun setCurrentClub(id: String?) {
        val club = GolfClub.fromId(id)
        dataStore.edit { p ->
            if (club == null) p.remove(KEY_CURRENT_CLUB) else p[KEY_CURRENT_CLUB] = club.id
        }
    }

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
        private val KEY_OWNED_CLUBS = stringSetPreferencesKey("ownedClubs")
        private val KEY_CURRENT_CLUB = stringPreferencesKey("currentClub")
    }
}

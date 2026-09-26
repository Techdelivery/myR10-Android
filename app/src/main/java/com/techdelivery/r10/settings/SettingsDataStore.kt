package com.techdelivery.r10.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File

/**
 * Production wiring: builds the on-device [SettingsRepository]. Kept separate
 * from the repository so the repository stays Context-free and unit-testable.
 */
object SettingsDataStore {
    private const val FILE_NAME = "r10_settings"

    @Volatile
    private var repository: SettingsRepository? = null

    /**
     * Process-wide singleton. DataStore enforces **one live instance per file**:
     * constructing a second one over the same path throws
     * `IllegalStateException: There are multiple DataStores active for the same file`.
     * The service runs `startDevice()` on every Start tap, so the store MUST be
     * shared rather than built per invocation.
     */
    fun get(context: Context): SettingsRepository {
        repository?.let { return it }
        synchronized(this) {
            repository?.let { return it }
            val store = produceStore(context.applicationContext.preferencesDataStoreFile(FILE_NAME))
            return SettingsRepository(store).also { repository = it }
        }
    }

    // Visible for tests: a real DataStore over an arbitrary file.
    fun produceStore(file: File): DataStore<Preferences> = PreferenceDataStoreFactory.create { file }
}

// Context extension lives here to avoid pulling Context into SettingsRepository.kt.
private fun Context.preferencesDataStoreFile(name: String): File =
    File(applicationContext.filesDir, "datastore/$name.preferences_pb")

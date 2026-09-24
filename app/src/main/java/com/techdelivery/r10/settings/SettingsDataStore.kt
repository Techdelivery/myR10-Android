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

    fun create(context: Context): SettingsRepository =
        SettingsRepository(produceStore(context.preferencesDataStoreFile(FILE_NAME)))

    // Visible for tests: a real DataStore over an arbitrary file.
    fun produceStore(file: File): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { file }
}

// Context extension lives here to avoid pulling Context into SettingsRepository.kt.
private fun Context.preferencesDataStoreFile(name: String): File =
    File(applicationContext.filesDir, "datastore/$name.preferences_pb")

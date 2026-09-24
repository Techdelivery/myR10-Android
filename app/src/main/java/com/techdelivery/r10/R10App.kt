package com.techdelivery.r10

import android.app.Application
import com.techdelivery.r10.settings.SettingsDataStore
import com.techdelivery.r10.settings.SettingsRepository

/**
 * Application entry. Manual wiring (no Hilt, per DESIGN §2): process-wide
 * singletons hang off here.
 *
 * The settings repository is exposed here because DataStore permits only one
 * live instance per backing file — building it per Start tap throws
 * "There are multiple DataStores active for the same file". Everything that
 * needs settings must go through this instance.
 */
class R10App : Application() {
    val settingsRepository: SettingsRepository by lazy { SettingsDataStore.get(this) }
}

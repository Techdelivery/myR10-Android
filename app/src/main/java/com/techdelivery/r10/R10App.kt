package com.techdelivery.r10

import android.app.Application
import com.techdelivery.r10.data.ShotProtoStore
import com.techdelivery.r10.settings.SettingsDataStore
import com.techdelivery.r10.settings.SettingsRepository
import java.io.File

/**
 * Application entry. Manual wiring (no Hilt, per DESIGN §2): process-wide
 * singletons hang off here.
 *
 * The settings repository is exposed here because DataStore permits only one
 * live instance per backing file — building it per Start tap throws
 * "There are multiple DataStores active for the same file". Everything that
 * needs settings must go through this instance.
 *
 * [shotStore] is likewise a process-wide singleton: one writer per file. It holds
 * the R10's own protobuf records (ROADMAP R7); the CSV is produced on export.
 */
class R10App : Application() {
    val settingsRepository: SettingsRepository by lazy { SettingsDataStore.get(this) }
    val shotStore: ShotProtoStore by lazy { ShotProtoStore(File(filesDir, "shots.bin")) }
}

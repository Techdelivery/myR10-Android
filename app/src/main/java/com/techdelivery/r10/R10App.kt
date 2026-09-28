package com.techdelivery.r10

import android.app.Application
import com.techdelivery.r10.data.ShotProtoStore
import com.techdelivery.r10.data.ShotWriteQueue
import com.techdelivery.r10.settings.SettingsDataStore
import com.techdelivery.r10.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 *
 * [shotWriteQueue] is the *only* thing that writes [shotStore] — the service that
 * receives shots and the UI that tags and deletes them both go through it, which is
 * what orders their writes (DESIGN §8). It is exposed here for the same reason the
 * store is: it needs an app-lifetime scope and a single instance, and it is started
 * eagerly so an edit from the Shots tab has a writer even when no monitor is
 * running. The service stops feeding it, never closes it — the history outlives any
 * one connection, and a queue closed on Stop would drop rows queued during teardown.
 */
class R10App : Application() {
    val settingsRepository: SettingsRepository by lazy { SettingsDataStore.get(this) }
    val shotStore: ShotProtoStore by lazy { ShotProtoStore(File(filesDir, "shots.bin")) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val shotWriteQueue: ShotWriteQueue by lazy {
        ShotWriteQueue(shotStore, appScope).also { it.start() }
    }
}

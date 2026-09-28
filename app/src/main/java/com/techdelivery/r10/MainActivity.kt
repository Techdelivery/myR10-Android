package com.techdelivery.r10

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.techdelivery.r10.club.GolfClub
import com.techdelivery.r10.data.ShotCsvFormat
import com.techdelivery.r10.data.ShotProtoStore
import com.techdelivery.r10.data.ShotWriteOp
import com.techdelivery.r10.data.ShotWriteQueue
import com.techdelivery.r10.data.WriteOutcome
import com.techdelivery.r10.settings.AppSettings
import com.techdelivery.r10.settings.SettingsRepository
import com.techdelivery.r10.state.DeviceStateHolder
import com.techdelivery.r10.ui.DeviceScreen
import com.techdelivery.r10.ui.SettingsScreen
import com.techdelivery.r10.ui.ShotsScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TABS = listOf("Device", "Shots", "Settings")

/** Index of the Shots tab in [TABS]; the tab that holds the screen awake. */
private const val SHOTS_TAB = 1

/**
 * What the user is told when a club tag or a delete was refused because the shot
 * history is damaged. Same shape as the persist sink's own errors: what failed,
 * then what to do about it.
 */
private const val REFUSED_HISTORY_WRITE =
    "could not save that change — shot history is damaged; export it before anything else"

/** Shown when the write queue will not run the change at all. */
private const val REFUSED_WRITE_NOT_QUEUED =
    "could not save that change — the shot writer is not running; restart the monitor and try again"

class MainActivity : ComponentActivity() {

    private var running by mutableStateOf(false)
    private var permissionDenied by mutableStateOf(false)

    // Own scope rather than lifecycleScope: avoids pulling lifecycle-runtime-ktx
    // in just for this, and is cancelled in onDestroy.
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                permissionDenied = false
                startMonitor()
            } else {
                permissionDenied = true
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as R10App
        val repo: SettingsRepository = app.settingsRepository
        val store: ShotProtoStore = app.shotStore
        loadHistory(store)
        val queue: ShotWriteQueue = app.shotWriteQueue
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val settings by repo.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
                    var tab by remember { mutableIntStateOf(0) }
                    val store = remember { (application as R10App).shotStore }

                    // ROADMAP R1: the screen sleeping mid-session loses the shot
                    // you just hit. Hold it awake on the Shots tab only, so the
                    // phone can still sleep in the pocket from any other tab.
                    // The window flag only applies while our window is focused, so
                    // backgrounding the app does not pin the screen either.
                    val windowView = LocalView.current
                    DisposableEffect(tab) {
                        windowView.keepScreenOn = tab == SHOTS_TAB
                        onDispose { windowView.keepScreenOn = false }
                    }
                    Column(
                        // targetSdk 36 forces edge-to-edge: without inset handling the
                        // first child (the Start/Stop button) draws under the status bar.
                        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { if (running) stopMonitor() else requestStart() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (running) "Stop monitor" else "Start monitor")
                        }
                        if (permissionDenied) {
                            Text(
                                "Bluetooth/notification permission denied. Grant in system Settings to scan and connect.",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        TabRow(selectedTabIndex = tab) {
                            TABS.forEachIndexed { i, title ->
                                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                            }
                        }
                        when (tab) {
                            0 -> DeviceScreen(showHexLog = settings.debugLogging, modifier = Modifier.fillMaxSize())

                            1 -> ShotsScreen(
                                ownedClubs = settings.ownedClubs,
                                onSetClub = { shotId, at, club -> setShotClub(repo, queue, shotId, at, club) },
                                onDeleteShot = { shotId, at -> deleteShot(queue, shotId, at) },
                                modifier = Modifier.fillMaxSize(),
                            )

                            else -> SettingsScreen(
                                repo = repo,
                                onExportCsv = { exportCsv(store) },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * M3: show persisted shot history even before this session connects.
     *
     * Loaded through [ShotProtoStore.loadAllWithReport] so a file too large to read
     * whole says so on the Shots tab, instead of handing back a history that quietly
     * stops partway and leaving the user to think those shots never happened.
     */
    private fun loadHistory(store: ShotProtoStore) {
        uiScope.launch {
            val load = runCatching { store.loadAll() }.getOrNull()
            if (load == null) {
                DeviceStateHolder.adoptHistory(emptyList())
                // A missing or empty file is not a throw, so anything reaching here
                // is a real fault. Showing an empty history and saying nothing is
                // the failure mode this is meant to avoid.
                DeviceStateHolder.historyError.value = "could not read the shot history"
                return@launch
            }
            // Merge, never assign: a shot can land while the file is being read.
            DeviceStateHolder.adoptHistory(load.shots)
            load.problems.firstOrNull()?.let { DeviceStateHolder.historyError.value = it }
        }
    }

    /**
     * R5: write the club annotation, then mirror it into the live list. The queue
     * owns the file and reports what it actually did; the holder is only what the UI
     * reads.
     *
     * The pick also becomes the arrival stamp for subsequent shots, which is what
     * saves a tap per ball at the range — so it is written even when the visible
     * shot already carried that label. Both writes hang off the annotation having
     * been stored: a stamp the user can see applied to every later shot must never
     * outlive a change that was refused.
     *
     * Going through the queue is what makes this work for a shot that is not on disk
     * yet: the shot's own append is ahead of this edit in the same FIFO, so the edit
     * lands on a row that exists instead of matching nothing (DESIGN §8).
     */
    private fun setShotClub(
        repo: SettingsRepository,
        queue: ShotWriteQueue,
        shotId: Int,
        receivedAtMs: Long,
        club: GolfClub?,
    ) {
        uiScope.launch {
            val result = queue.apply(ShotWriteOp.SetClub(shotId, receivedAtMs, club))
            if (result == WriteOutcome.WRITTEN) {
                DeviceStateHolder.setShotClub(shotId, receivedAtMs, club?.id)
                if (club != null) runCatching { repo.setCurrentClub(club.id) }
            } else {
                reportRefusedHistoryWrite(result)
            }
        }
    }

    /**
     * R6: delete the row, then drop it from the live list. A failed delete leaves
     * the UI alone — showing a shot as gone while it is still on disk (and still in
     * the next export) is worse than an error.
     */
    private fun deleteShot(queue: ShotWriteQueue, shotId: Int, receivedAtMs: Long) {
        uiScope.launch {
            val result = queue.apply(ShotWriteOp.DeleteShot(shotId, receivedAtMs))
            if (result == WriteOutcome.WRITTEN) {
                DeviceStateHolder.removeShot(shotId, receivedAtMs)
            } else {
                reportRefusedHistoryWrite(result)
            }
        }
    }

    /**
     * A refused write is a no-op, and a no-op the user cannot see reads as a broken
     * app: they pick a club or confirm a delete and nothing happens. The Shots tab
     * already renders [DeviceStateHolder.historyError], so the refusal goes there.
     *
     * Only a real fault is worth words. A shot that is simply not on disk is a no-op
     * — with the queue ordering writes, the only way to see one is a stale id — and
     * claiming the history is damaged when nothing is damaged is its own lie.
     */
    private fun reportRefusedHistoryWrite(result: WriteOutcome) {
        val message = when (result) {
            WriteOutcome.DAMAGED -> REFUSED_HISTORY_WRITE
            WriteOutcome.REJECTED -> REFUSED_WRITE_NOT_QUEUED
            else -> return
        }
        DeviceStateHolder.historyError.value = message
    }

    private suspend fun exportCsv(store: ShotProtoStore): String {
        val dir = getExternalFilesDir(null) ?: filesDir
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out: File = store.exportCsv(dir, stamp)
        // Validate what was just written instead of trusting the copy: a row that
        // does not decode is skipped, so a torn file used to export as a success
        // with rows silently missing (ROADMAP R4).
        val v = ShotCsvFormat.validateFile(out)
        // ...and validate the source too. The CSV is a derived copy, so it is
        // well-formed by construction: validating only the copy reports a healthy
        // export while damaged records were dropped on the way out. Asked of the
        // store after the export returns, because validate() takes the store's mutex.
        val storeProblems = runCatching { store.validate() }.getOrNull()?.problems.orEmpty()
        val damage = if (storeProblems.isEmpty()) {
            ""
        } else {
            " · store check: ${storeProblems.size} problem(s): ${storeProblems.take(3).joinToString("; ")}"
        }
        return "Wrote ${out.name} (${out.length()} bytes) · ${v.summary()}$damage\n${out.absolutePath}"
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        return perms.toTypedArray()
    }

    private fun requestStart() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            startMonitor()
        }
    }

    private fun startMonitor() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter?.isEnabled != true) {
            permissionDenied = true
            return
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, R10ForegroundService::class.java).setAction(R10ForegroundService.ACTION_START),
        )
        running = true
    }

    private fun stopMonitor() {
        startService(
            Intent(this, R10ForegroundService::class.java).setAction(R10ForegroundService.ACTION_STOP),
        )
        running = false
    }
}

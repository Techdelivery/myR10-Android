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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.techdelivery.r10.data.ShotCsvStore
import com.techdelivery.r10.settings.AppSettings
import com.techdelivery.r10.settings.SettingsRepository
import com.techdelivery.r10.state.DeviceStateHolder
import com.techdelivery.r10.state.MAX_LIVE_SHOTS
import com.techdelivery.r10.ui.DeviceScreen
import com.techdelivery.r10.ui.SettingsScreen
import com.techdelivery.r10.ui.ShotsScreen
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private val TABS = listOf("Device", "Shots", "Settings")

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
        val store: ShotCsvStore = app.shotStore
        loadHistory(store)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val settings by repo.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
                    var tab by remember { mutableIntStateOf(0) }
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
                            1 -> ShotsScreen(Modifier.fillMaxSize())
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

    /** M3: show persisted shot history even before this session connects. */
    private fun loadHistory(store: ShotCsvStore) {
        uiScope.launch {
            if (DeviceStateHolder.shots.value.isNotEmpty()) return@launch
            val history = runCatching { store.loadAll() }.getOrDefault(emptyList())
            if (history.isEmpty()) return@launch
            DeviceStateHolder.shots.value = history.asReversed().take(MAX_LIVE_SHOTS)
            DeviceStateHolder.shotCount.value = history.size
        }
    }

    private suspend fun exportCsv(store: ShotCsvStore): String {
        val dir = getExternalFilesDir(null) ?: filesDir
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out: File = store.exportSnapshot(dir, stamp)
        return "Wrote ${out.name} (${out.length()} bytes)\n${out.absolutePath}"
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

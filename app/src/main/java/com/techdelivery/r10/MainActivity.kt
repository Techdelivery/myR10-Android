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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.techdelivery.r10.ui.DeviceScreen

class MainActivity : ComponentActivity() {

    private var running by mutableStateOf(false)
    private var permissionDenied by mutableStateOf(false)

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
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { if (running) stopMonitor() else requestStart() }) {
                            Text(if (running) "Stop monitor" else "Start monitor")
                        }
                        if (permissionDenied) {
                            Text(
                                "Bluetooth/notification permission denied. Grant in system Settings to scan and connect.",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        DeviceScreen(Modifier.fillMaxSize())
                    }
                }
            }
        }
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

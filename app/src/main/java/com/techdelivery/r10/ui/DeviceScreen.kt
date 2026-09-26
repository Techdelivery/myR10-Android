package com.techdelivery.r10.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.techdelivery.r10.protocol.util.HexDirection
import com.techdelivery.r10.protocol.util.HexEntry
import com.techdelivery.r10.state.ConnState
import com.techdelivery.r10.state.DeviceStateHolder
import kotlinx.coroutines.delay

/** Hex pane poll interval while the pane is visible (~2 Hz). */
private const val HEX_REFRESH_MS = 500L

/**
 * DESIGN §9 tab 1 — connection state, device info, live device readouts,
 * §7.2 error/calibration surfacing, and (when `debugLogging` is on) the hex pane.
 */
@Composable
fun DeviceScreen(showHexLog: Boolean, modifier: Modifier = Modifier) {
    val conn by DeviceStateHolder.connectionState.collectAsState()
    val info by DeviceStateHolder.deviceInfo.collectAsState()
    val wake by DeviceStateHolder.wakeUpStatus.collectAsState()
    val stateType by DeviceStateHolder.stateType.collectAsState()
    val tilt by DeviceStateHolder.tilt.collectAsState()
    val error by DeviceStateHolder.errorMessage.collectAsState()
    val alertError by DeviceStateHolder.activeError.collectAsState()
    val calibration by DeviceStateHolder.calibration.collectAsState()
    val shotCount by DeviceStateHolder.shotCount.collectAsState()

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("R10 Monitor", style = MaterialTheme.typography.headlineSmall)
        Text("Status: ${conn.name}", style = MaterialTheme.typography.titleMedium)

        if (conn == ConnState.ERROR && error != null) {
            Text("Error: $error", color = MaterialTheme.colorScheme.error)
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Labeled("Model", info.model)
                Labeled("Firmware", info.firmware)
                Labeled("Serial", info.serial)
                Labeled("Battery", if (info.batteryLevel >= 0) "${info.batteryLevel}%" else "—")
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Labeled("WakeUp", wake ?: "—")
                Labeled("State", stateType ?: "—")
                Labeled("Tilt", tilt ?: "—")
                Labeled("Shots", "$shotCount")
            }
        }

        // §7.2: a device-reported error stays visible until a healthy state clears it.
        alertError?.let { e ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "${e.code.name} — ${e.severity.name}",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (e.rollDeg != null || e.pitchDeg != null) {
                        Text(
                            "device tilt roll=${e.rollDeg} pitch=${e.pitchDeg}",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        calibration?.let { c ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Labeled("Tilt calibration", c.status.name)
                    Labeled("Result", c.result?.name ?: "—")
                }
            }
        }

        if (showHexLog) {
            HexLogPane(Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun Labeled(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text("$label: ", style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun HexLogPane(modifier: Modifier = Modifier) {
    var entries by remember { mutableStateOf<List<HexEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            entries = DeviceStateHolder.hexLog.snapshot()
            delay(HEX_REFRESH_MS) // ~2 Hz while visible
        }
    }
    Text("Hex log (${entries.size})", style = MaterialTheme.typography.labelLarge)
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
    ) {
        for (e in entries) {
            Text(
                text = "${e.direction.name}  ${e.bytes.joinToString(" ") { "%02X".format(it) }}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = if (e.direction == HexDirection.TX) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.tertiary
                },
            )
        }
    }
}

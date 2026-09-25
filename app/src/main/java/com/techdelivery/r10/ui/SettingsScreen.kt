package com.techdelivery.r10.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.techdelivery.r10.settings.AppSettings
import com.techdelivery.r10.settings.SettingsRepository
import com.techdelivery.r10.settings.asDoubleRange
import kotlinx.coroutines.launch
import java.util.Locale

/** Feet per metre — used to echo the tee distance in metric next to the imperial input. */
private const val FT_PER_M = 3.281f

/**
 * DESIGN §9 tab 3 — every §8 settings key, persisted straight to DataStore.
 * Numeric keys use steppers so there is no half-typed value to parse.
 */
@Composable
fun SettingsScreen(repo: SettingsRepository, onExportCsv: suspend () -> String, modifier: Modifier = Modifier) {
    val settings by repo.settings.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    var nameDraft by remember { mutableStateOf(settings.deviceName) }
    var exportMsg by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }

    // Adopt the persisted name when it changes elsewhere, but don't fight the user's typing.
    LaunchedEffect(settings.deviceName) { if (nameDraft.isBlank()) nameDraft = settings.deviceName }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Connection", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    label = { Text("Device name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { scope.launch { repo.setDeviceName(nameDraft.trim().ifBlank { "Approach R10" }) } },
                        enabled = nameDraft != settings.deviceName,
                    ) { Text("Save name") }
                    OutlinedButton(
                        onClick = { nameDraft = settings.deviceName },
                        enabled = nameDraft != settings.deviceName,
                    ) { Text("Reset") }
                }
                StepperRow(
                    label = "Reconnect interval",
                    value = settings.reconnectIntervalS.toDouble(),
                    step = 1.0,
                    unit = "s",
                    range = AppSettings.RECONNECT_INTERVAL_S.asDoubleRange(),
                    onValue = { scope.launch { repo.setReconnectIntervalS(it.toInt()) } },
                )
            }
        }

        Text("Behaviour", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SwitchRow(
                    label = "Auto-wake on STANDBY",
                    checked = settings.autoWake,
                    onChange = { scope.launch { repo.setAutoWake(it) } },
                )
                SwitchRow(
                    label = "Calibrate tilt on connect",
                    checked = settings.calibrateTiltOnConnect,
                    onChange = { scope.launch { repo.setCalibrateTiltOnConnect(it) } },
                )
                SwitchRow(
                    label = "Debug logging (hex pane)",
                    checked = settings.debugLogging,
                    onChange = { scope.launch { repo.setDebugLogging(it) } },
                )
            }
        }

        Text("Shot conditions (§8)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StepperRow(
                    label = "Temperature",
                    value = settings.temperature.toDouble(),
                    step = 1.0,
                    unit = "°F",
                    range = AppSettings.TEMPERATURE_F.asDoubleRange(),
                    onValue = { scope.launch { repo.setTemperature(it.toInt()) } },
                )
                StepperRow(
                    label = "Humidity",
                    value = settings.humidity.toDouble(),
                    step = 1.0, // stored as Int (AppSettings) — a 0.1 step would be truncated
                    unit = "",
                    range = AppSettings.HUMIDITY.asDoubleRange(),
                    onValue = { scope.launch { repo.setHumidity(it.toInt()) } },
                )
                Text(
                    "stored as an Int, so only 0 or 1 — a 0..100 % model needs a settings migration",
                    style = MaterialTheme.typography.bodySmall,
                )
                StepperRow(
                    label = "Altitude",
                    value = settings.altitude.toDouble(),
                    step = 10.0,
                    unit = "m",
                    range = AppSettings.ALTITUDE_M.asDoubleRange(),
                    onValue = { scope.launch { repo.setAltitude(it.toInt()) } },
                )
                StepperRow(
                    label = "Air density",
                    value = settings.airDensity,
                    step = 0.01,
                    unit = "",
                    range = AppSettings.AIR_DENSITY,
                    onValue = { scope.launch { repo.setAirDensity(it) } },
                )
                StepperRow(
                    label = "Tee distance",
                    value = settings.teeDistanceFt.toDouble(),
                    step = 1.0,
                    unit = "ft",
                    range = AppSettings.TEE_DISTANCE_FT.asDoubleRange(),
                    onValue = { scope.launch { repo.setTeeDistanceFt(it.toInt()) } },
                )
                val teeRangeMeters = String.format(Locale.US, "%.3f", settings.teeDistanceFt / FT_PER_M)
                Text(
                    "tee_range sent to device = $teeRangeMeters m",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Text("Shot history (M3)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !exporting,
                    onClick = {
                        exportMsg = null
                        exporting = true
                        scope.launch {
                            exportMsg = runCatching { onExportCsv() }
                                .getOrElse { "Export failed: ${it.message}" }
                            exporting = false
                        }
                    },
                ) { Text(if (exporting) "Exporting…" else "Export shots to CSV") }
                exportMsg?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * Bounded +/− stepper. [range] is the same envelope the repository clamps to, so
 * the buttons disable exactly where the setter would clamp.
 */
@Composable
private fun StepperRow(
    label: String,
    value: Double,
    step: Double,
    unit: String,
    range: ClosedFloatingPointRange<Double>,
    onValue: (Double) -> Unit,
) {
    val canDecrement = value > range.start
    val canIncrement = value < range.endInclusive
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { onValue((value - step).coerceAtLeast(range.start)) },
                enabled = canDecrement,
            ) { Text("−") }
            Text(
                formatValue(value) + if (unit.isEmpty()) "" else " $unit",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = { onValue((value + step).coerceAtMost(range.endInclusive)) },
                enabled = canIncrement,
            ) { Text("+") }
        }
    }
}

/** Trim trailing zeros: 1.0 -> "1", 0.01 -> "0.01". */
private fun formatValue(v: Double): String {
    val rounded = Math.rint(v * 1000.0) / 1000.0
    return if (rounded == Math.rint(rounded)) {
        Math.rint(rounded).toInt().toString()
    } else {
        rounded.toString()
    }
}

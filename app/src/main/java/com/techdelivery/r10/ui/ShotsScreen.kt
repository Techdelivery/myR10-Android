package com.techdelivery.r10.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import LaunchMonitor.Proto.R10Protos
import com.techdelivery.r10.protocol.shot.Shot
import com.techdelivery.r10.state.DeviceStateHolder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ShotFilter { ALL, PRACTICE, NORMAL }

/**
 * DESIGN §9 tab 2 — latest shot detail card plus the scrolling shot list.
 * Shots arrive already deduplicated by `shot_id` (DESIGN §7.2).
 */
@Composable
fun ShotsScreen(modifier: Modifier = Modifier) {
    val shots by DeviceStateHolder.shots.collectAsState()
    val historyError by DeviceStateHolder.historyError.collectAsState()
    var filter by remember { mutableStateOf(ShotFilter.ALL) }

    val visible = when (filter) {
        ShotFilter.ALL -> shots
        ShotFilter.PRACTICE -> shots.filter { it.isPractice }
        ShotFilter.NORMAL -> shots.filter { !it.isPractice }
    }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShotFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(f.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        historyError?.let { msg ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Text(
                    msg,
                    Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (visible.isEmpty()) {
            Text(
                if (shots.isEmpty()) "No shots yet. Hit a ball with the R10 connected." else "No shots match this filter.",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }

        LatestShotCard(visible.first())

        Text(
            "${visible.size} shot${if (visible.size == 1) "" else "s"} (newest first)",
            style = MaterialTheme.typography.labelLarge,
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(visible, key = { "${it.shotId}-${it.receivedAtMs}" }) { shot ->
                ShotRow(shot)
            }
        }
    }
}

@Composable
private fun LatestShotCard(shot: Shot) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Shot ${shot.shotId} · ${shot.shotType.name}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            shot.ball?.let { b ->
                BigStat("Ball speed", fmt(b.ballSpeedMph, 1), "mph")
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    BigStat("Launch angle", fmt(b.launchAngleDeg, 1), "°")
                    BigStat("Launch dir", fmt(b.launchDirectionDeg, 1), "°")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    BigStat("Total spin", fmt(b.totalSpinRpm, 0), "rpm")
                    BigStat("Back spin", fmt(b.backSpinRpm, 0), "rpm")
                    BigStat("Side spin", fmt(b.sideSpinRpm, 0), "rpm")
                }
                BigStat("Spin axis", fmt(b.spinAxisDeg, 1), "°")
            }
            shot.club?.let { c ->
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    BigStat("Club speed", fmt(c.clubSpeedMph, 1), "mph")
                    BigStat("Face", fmt(c.faceAngleDeg, 1), "°")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    BigStat("Path", fmt(c.pathDeg, 1), "°")
                    BigStat("Attack angle", fmt(c.attackAngleDeg, 1), "°")
                }
            }
            shot.swing?.let { s ->
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    BigStat("Tempo", s.tempo?.let { fmt(it, 2) } ?: "—", "")
                    BigStat("Backswing", "${s.backswingDurationUs} µs", "")
                    BigStat("Downswing", "${s.downswingDurationUs} µs", "")
                }
            }
        }
    }
}

@Composable
private fun BigStat(label: String, value: String, unit: String) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text("$label${if (unit.isEmpty()) "" else " ($unit)"}", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ShotRow(shot: Shot) {
    val b = shot.ball
    val c = shot.club
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "#${shot.shotId}\n${timeFormat.format(Date(shot.receivedAtMs))}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                b?.let { "${fmt(it.ballSpeedMph, 1)} mph" } ?: "—",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                b?.let { "${fmt(it.launchAngleDeg, 1)}° / ${fmt(it.totalSpinRpm, 0)} rpm" } ?: "—",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                c?.let { "${fmt(it.clubSpeedMph, 1)} mph" } ?: "—",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

/** DESIGN §6: `Metrics.ShotType` PRACTICE vs NORMAL. */
private val Shot.isPractice: Boolean
    get() = shotType == R10Protos.Metrics.ShotType.PRACTICE

private fun fmt(v: Double, decimals: Int): String = String.format(Locale.US, "%.${decimals}f", v)

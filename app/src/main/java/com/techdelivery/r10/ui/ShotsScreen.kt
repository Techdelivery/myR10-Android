package com.techdelivery.r10.ui

import LaunchMonitor.Proto.R10Protos
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.techdelivery.r10.club.GolfClub
import com.techdelivery.r10.protocol.alert.DeviceAlert
import com.techdelivery.r10.protocol.shot.Shot
import com.techdelivery.r10.state.DeviceStateHolder
import com.techdelivery.r10.state.TiltReading
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ShotFilter { ALL, PRACTICE, NORMAL }

/**
 * DESIGN §9 tab 2 — shot detail plus the scrolling shot list.
 * Shots arrive already deduplicated by `shot_id` (DESIGN §7.2).
 *
 * The detail card follows the **selected** shot (ROADMAP R3), falling back to the
 * newest when nothing is selected, so a new shot never steals a deliberate
 * selection. The tab also surfaces the device's own level warning (ROADMAP R2),
 * because the Shots tab is where you are looking when you hit — not the Device tab.
 */
@Composable
fun ShotsScreen(
    /**
     * Clubs the user owns (ROADMAP R5). The picker offers these; an empty set falls
     * back to the full canonical list, because an unlabelable shot is worse than an
     * unowned club.
     */
    ownedClubs: Set<String>,
    /** Persist a club annotation ([GolfClub] or null to clear) on the shot with this id and arrival time. */
    onSetClub: (Int, Long, GolfClub?) -> Unit,
    /** Delete the shot permanently. The caller confirms with the user first. */
    onDeleteShot: (Int, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shots by DeviceStateHolder.shots.collectAsState()
    val historyError by DeviceStateHolder.historyError.collectAsState()
    val activeError by DeviceStateHolder.activeError.collectAsState()
    val tiltReading by DeviceStateHolder.tiltReading.collectAsState()
    var filter by remember { mutableStateOf(ShotFilter.ALL) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<Shot?>(null) }

    val visible = when (filter) {
        ShotFilter.ALL -> shots
        ShotFilter.PRACTICE -> shots.filter { it.isPractice }
        ShotFilter.NORMAL -> shots.filter { !it.isPractice }
    }

    // A selection that filtered out of view falls back to the newest rather than
    // showing an empty detail card.
    val newest = visible.firstOrNull()
    val selected = visible.firstOrNull { shotKey(it) == selectedKey }
    val shown = selected ?: newest

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

        TiltBanner(activeError, tiltReading)
        historyError?.let { HistoryErrorBanner(it) }

        if (visible.isEmpty()) {
            Text(
                if (shots.isEmpty()) {
                    "No shots yet. Hit a ball with the R10 connected."
                } else {
                    "No shots match this filter."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            ShotsScreenBody(
                visible = visible,
                selected = selected,
                newest = newest,
                ownedClubs = ownedClubs,
                actions = ShotActions(
                    onSelect = { key -> selectedKey = if (key == selectedKey) null else key },
                    onClearSelection = { selectedKey = null },
                    onSetClub = onSetClub,
                    onRequestDelete = { pendingDelete = it },
                ),
            )
        }
    }

    // ROADMAP R6: deletion is data loss with no undo, so it is confirmed by name
    // rather than fired from a row tap. Only the selected shot can be reached here.
    pendingDelete?.let { shot ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete shot #${shot.shotId}?") },
            text = {
                Text(
                    buildString {
                        append(timeFormat.format(Date(shot.receivedAtMs)))
                        shot.ball?.let { append(" · ${fmt(it.ballSpeedMph, 1)} mph") }
                        shot.clubLabel?.let { append(" · $it") }
                        append("\n\nThis cannot be undone. An exported CSV is the only way back.")
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        // The selected shot is gone; fall back rather than show an empty card.
                        selectedKey = null
                        onDeleteShot(shot.shotId, shot.receivedAtMs)
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

/** The clubs the picker offers: the owned set, or the whole bag when it is empty. */
private fun pickerClubs(owned: Set<String>): List<GolfClub> =
    GolfClub.ALL.filter { it.id in owned }.ifEmpty { GolfClub.ALL }

/**
 * The Shots-tab interactions, bundled so the layout composable takes one callback
 * object instead of a growing parameter list.
 */
private class ShotActions(
    val onSelect: (String?) -> Unit,
    val onClearSelection: () -> Unit,
    val onSetClub: (Int, Long, GolfClub?) -> Unit,
    val onRequestDelete: (Shot) -> Unit,
)

/**
 * The detail card plus the scrolling list, split out of [ShotsScreen] so that
 * function composes state and this one composes layout.
 */
@Composable
private fun ColumnScope.ShotsScreenBody(
    visible: List<Shot>,
    selected: Shot?,
    newest: Shot?,
    ownedClubs: Set<String>,
    actions: ShotActions,
) {
    val shown = selected ?: newest
    shown?.let {
        ShotDetailCard(
            shot = it,
            isSelected = it === selected,
            isLatest = it === newest,
            offeredClubs = pickerClubs(ownedClubs),
            onSetClub = { club -> actions.onSetClub(it.shotId, it.receivedAtMs, club) },
            onRequestDelete = actions.onRequestDelete,
        )
    }

    if (selected != null && selected !== newest) {
        TextButton(onClick = actions.onClearSelection) { Text("Show latest shot") }
    }

    Text(
        "${visible.size} shot${if (visible.size == 1) "" else "s"} (newest first) · " +
            "tap a shot to inspect it",
        style = MaterialTheme.typography.labelLarge,
    )
    LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(visible, key = { shotKey(it) }) { shot ->
            ShotRow(shot = shot, selected = shot === selected, onClick = { actions.onSelect(shotKey(shot)) })
        }
    }
}

/**
 * ROADMAP R2: the R10's own level verdict, surfaced on the Shots tab. The
 * device's PLATFORM_TILTED error is the trigger (not an app-invented threshold,
 * which can disagree with the unit's real tolerance); the live level is shown
 * otherwise so "flat enough" is visible rather than assumed.
 */
@Composable
private fun TiltBanner(activeError: DeviceAlert.ErrorAlert?, tiltReading: TiltReading?) {
    val tilted = activeError?.code == R10Protos.Error.ErrorCode.PLATFORM_TILTED
    val bannerTilt: TiltReading? = tiltReading ?: activeError?.let { e ->
        val roll = e.rollDeg
        val pitch = e.pitchDeg
        if (roll != null && pitch != null) TiltReading(roll, pitch) else null
    }
    when {
        tilted -> TiltWarningCard(bannerTilt)

        bannerTilt != null -> Text(
            "R10 level · pitch ${fmt(bannerTilt.pitchDeg.toDouble(), 1)}° " +
                "roll ${fmt(bannerTilt.rollDeg.toDouble(), 1)}°",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A shot-persistence failure, surfaced while the link itself is healthy. */
@Composable
private fun HistoryErrorBanner(msg: String) {
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

/**
 * ROADMAP R2: the R10 refuses to record while it is not level. That used to be
 * visible only on the Device tab, so a session produced nothing and looked like a
 * dead app.
 */
@Composable
private fun TiltWarningCard(tilt: TiltReading?) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "R10 is not level — it will not record shots",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                tilt?.let {
                    "pitch ${fmt(it.pitchDeg.toDouble(), 1)}° · roll ${fmt(it.rollDeg.toDouble(), 1)}°. " +
                        "Lay the unit flat on the ground and it will clear by itself."
                } ?: "Lay the unit flat on the ground and it will clear by itself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun ShotDetailCard(
    shot: Shot,
    isSelected: Boolean,
    isLatest: Boolean,
    offeredClubs: List<GolfClub>,
    onSetClub: (GolfClub?) -> Unit,
    onRequestDelete: (Shot) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Shot ${shot.shotId} · ${shot.shotType.name}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    timeFormat.format(Date(shot.receivedAtMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Say which shot this is, so "selected" is never ambiguous with "latest".
            Text(
                when {
                    isSelected -> "selected"
                    isLatest -> "latest shot"
                    else -> ""
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            // ROADMAP R5: the club is a user annotation, never device data, so it is
            // editable here and only ever overwritten on purpose. Picking one also
            // becomes the arrival stamp for the next shots.
            ClubEditor(shot.clubLabel, offeredClubs, onSetClub)
            TextButton(
                onClick = { onRequestDelete(shot) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Delete this shot", color = MaterialTheme.colorScheme.error) }
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

/**
 * The club picker for the selected shot (ROADMAP R5).
 *
 * Only the owned clubs are offered, and "Clear" appears only when there is
 * something to clear — clearing is a separate, non-destructive action from
 * deleting the shot.
 */
@Composable
private fun ClubEditor(clubLabel: String?, offeredClubs: List<GolfClub>, onSetClub: (GolfClub?) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text("Club", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (clubLabel != null) {
                TextButton(onClick = { onSetClub(null) }) { Text("Clear") }
            }
            Box {
                TextButton(onClick = { menuOpen = true }) { Text(clubLabel ?: "Pick a club") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    offeredClubs.forEach { club ->
                        DropdownMenuItem(
                            text = { Text(club.id) },
                            onClick = {
                                menuOpen = false
                                onSetClub(club)
                            },
                        )
                    }
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
private fun ShotRow(shot: Shot, selected: Boolean, onClick: () -> Unit) {
    val b = shot.ball
    val c = shot.club
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // The marker is a shape+weight change, not a colour change alone, so the
            // selection survives a colour-blind palette or a sunlight-washed screen.
            Text(
                if (selected) "▶" else "   ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            // The club leads the second line when it is known, so a tagged shot
            // reads "7-iron · 155 mph" at a glance; the time stays either way.
            Text(
                buildString {
                    append("#${shot.shotId}\n")
                    append(timeFormat.format(Date(shot.receivedAtMs)))
                    shot.clubLabel?.let { append(" · ").append(it) }
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
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

/** Stable identity for a shot in the list; matches the LazyColumn key. */
private fun shotKey(shot: Shot): String = "${shot.shotId}-${shot.receivedAtMs}"

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

/** DESIGN §6: `Metrics.ShotType` PRACTICE vs NORMAL. */
private val Shot.isPractice: Boolean
    get() = shotType == R10Protos.Metrics.ShotType.PRACTICE

private fun fmt(v: Double, decimals: Int): String = String.format(Locale.US, "%.${decimals}f", v)

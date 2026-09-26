package com.techdelivery.r10.data

import LaunchMonitor.Proto.R10Protos
import com.techdelivery.r10.protocol.shot.BallDisplay
import com.techdelivery.r10.protocol.shot.ClubDisplay
import com.techdelivery.r10.protocol.shot.Shot
import com.techdelivery.r10.protocol.shot.SwingDisplay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile

/**
 * Shot-history persistence (DESIGN §8 `Shot` entity, DESIGN §11 M3).
 *
 * DESIGN names Room. This build uses an append-only CSV store instead, for two
 * reasons recorded in TODO.md:
 *  - KSP has no release for the Kotlin version this project pins (2.4.x compiler),
 *    and wiring Room through kapt was rejected: this build container is capped at
 *    2 GiB and the extra annotation-processing round is what tips the build over.
 *  - The persisted shape is flat, numeric, and app-owned, so CSV is lossless here —
 *    and it doubles as the M3 CSV export with no second serializer.
 *
 * Swapping in Room later means replacing this class only; nothing else reads the file.
 *
 * Thread-safe: one [Mutex] guards the file. All fields are numbers, enum names, or
 * hex, so no CSV quoting/escaping is needed — that is deliberate.
 */
class ShotCsvStore(
    private val file: File,
    /**
     * How many recent dedup keys to keep. A constructor parameter rather than only
     * the constant so LRU eviction is testable without writing thousands of rows.
     */
    private val recentKeyWindow: Int = RECENT_KEY_WINDOW,
) {

    init {
        require(recentKeyWindow >= 1) { "recentKeyWindow must be at least 1" }
    }

    private val mutex = Mutex()

    /** Recently-persisted dedup keys, for cross-session duplicate rejection. */
    private val persistedKeys = HashSet<String>()
    private val keyOrder = ArrayDeque<String>()
    private var indexed = false

    /**
     * Append one shot. Returns true when the row was written, false when an
     * identical shot is already stored.
     *
     * DESIGN §8 asks for cross-session dedup through a `deviceShotId` unique index.
     * That key cannot be used literally: the R10 restarts its shot-id sequence on
     * every power cycle (see `ShotDeduper`), so a global unique constraint on
     * `shot_id` would reject legitimate new shots. The stable identity of a
     * re-pushed shot is its bytes, so the key here is the raw proto payload — same
     * frame, same key, whatever `shot_id` or receive time it picked up on the way
     * back. Only the last [RECENT_KEY_WINDOW] keys are held in memory; a reconnect
     * replay lands inside that window, and anything older is re-appended rather
     * than risking a false drop of a real shot.
     */
    suspend fun append(shot: Shot): Boolean = mutex.withLock {
        ensureIndexedUnlocked()
        val key = dedupKey(shot)
        if (key != null && key in persistedKeys) {
            touchUnlocked(key)
            return@withLock false
        }
        val needsHeader = !file.exists() || file.length() == 0L
        appendDurable((if (needsHeader) "$HEADER\n" else "") + encode(shot) + "\n")
        if (key != null) rememberUnlocked(key)
        true
    }

    /** Bulk append, skipping anything already stored. */
    suspend fun appendAll(shots: List<Shot>) = mutex.withLock {
        ensureIndexedUnlocked()
        val sb = StringBuilder()
        var wrote = 0
        shots.forEach {
            val key = dedupKey(it)
            if (key != null && key in persistedKeys) {
                touchUnlocked(key)
                return@forEach
            }
            if (wrote == 0 && (!file.exists() || file.length() == 0L)) sb.append(HEADER).append('\n')
            sb.append(encode(it)).append('\n')
            wrote++
            if (key != null) rememberUnlocked(key)
        }
        if (wrote > 0) appendDurable(sb.toString())
    }

    /** Oldest first, as stored. Corrupt lines are skipped, never fatal. */
    suspend fun loadAll(): List<Shot> = mutex.withLock { readUnlocked() }

    suspend fun clear() = mutex.withLock {
        runCatching { file.delete() }
        // Reset the index and force a re-read: if the delete failed the surviving
        // rows must still be known to the dedup set.
        persistedKeys.clear()
        keyOrder.clear()
        indexed = false
    }

    /** Snapshot text (header + rows) for a bug report. */
    suspend fun exportText(): String = mutex.withLock { snapshotText() }

    /** Write a timestamped copy into [dir]; returns the file. Used by the Export button. */
    suspend fun exportSnapshot(dir: File, stamp: String): File = mutex.withLock {
        dir.mkdirs()
        val out = File(dir, "r10-shots-$stamp.csv")
        out.writeText(snapshotText())
        out
    }

    /**
     * Content key for cross-session dedup, or null when the shot carries no raw
     * payload. A null key means "cannot be deduped" and is always written — an
     * empty payload would otherwise make every such shot a duplicate of the first.
     *
     * `shot_id` is folded in even though the proto bytes already carry it, so a
     * shot whose id differs is never dropped on a payload collision.
     */
    private fun dedupKey(shot: Shot): String? =
        shot.rawMetrics.takeIf { it.isNotEmpty() }?.let { "${shot.shotId}|${toHex(it)}" }

    private fun rememberUnlocked(key: String) {
        if (!persistedKeys.add(key)) return
        keyOrder.addLast(key)
        evictUnlocked()
    }

    /**
     * Move a re-observed key to the most-recent end, so eviction is LRU rather
     * than FIFO. Without this a key that keeps getting replayed could be evicted
     * while long-dead ones stay.
     */
    private fun touchUnlocked(key: String) {
        if (keyOrder.remove(key)) keyOrder.addLast(key)
    }

    private fun evictUnlocked() {
        while (keyOrder.size > recentKeyWindow) {
            persistedKeys.remove(keyOrder.removeFirst())
        }
    }

    /**
     * Append text durably, in one synchronous write.
     *
     * `RandomAccessFile` in `"rwd"` mode pushes each update to the device before
     * returning, and `fd.sync()` covers platforms that treat `rwd` as advisory.
     * The point is that a process kill leaves either a whole record or nothing —
     * never a torn line that `decode` would silently drop, losing the shot.
     * Costs one fsync per shot (~1/s at play), which is the right trade.
     */
    private fun appendDurable(text: String) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rwd").use { raf ->
            raf.seek(raf.length())
            raf.write(text.toByteArray(Charsets.UTF_8))
            raf.fd.sync()
        }
    }

    private fun ensureIndexedUnlocked() {
        if (indexed) return
        indexed = true
        readUnlocked().forEach { shot -> dedupKey(shot)?.let { rememberUnlocked(it) } }
    }

    /** Non-locking snapshot — callers must already hold [mutex]. */
    private fun snapshotText(): String = if (!file.exists()) "$HEADER\n" else file.readText()

    private fun readUnlocked(): List<Shot> {
        if (!file.exists()) return emptyList()
        return file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != HEADER }
            .mapNotNull { decode(it) }
    }

    companion object {
        val HEADER: String = listOf(
            "shot_id", "shot_type", "received_at_ms",
            "ball_speed_mph", "launch_angle_deg", "launch_direction_deg",
            "spin_axis_deg", "total_spin_rpm", "side_spin_rpm", "back_spin_rpm",
            "club_speed_mph", "face_angle_deg", "path_deg", "attack_angle_deg",
            "backswing_start_us", "downswing_start_us", "impact_us",
            "follow_through_end_us", "end_recording_us", "tempo",
            "raw_metrics_hex",
        ).joinToString(",")

        private const val COLS = 21

        /** Index of the trailing `raw_metrics_hex` column in [HEADER]. */
        private const val COL_RAW_METRICS = 20

        /**
         * How many recent dedup keys to keep in memory for cross-session duplicate
         * rejection. Sized well above any realistic reconnect-replay burst while
         * keeping the set bounded (~2 KB/shot of raw payload hex).
         */
        const val RECENT_KEY_WINDOW = 2_000

        fun encode(s: Shot): String {
            val b = s.ball
            val c = s.club
            val w = s.swing
            return listOf(
                s.shotId.toString(),
                s.shotType.name,
                s.receivedAtMs.toString(),
                b.orBlank { fmt(it.ballSpeedMph) },
                b.orBlank { fmt(it.launchAngleDeg) },
                b.orBlank { fmt(it.launchDirectionDeg) },
                b.orBlank { fmt(it.spinAxisDeg) },
                b.orBlank { fmt(it.totalSpinRpm) },
                b.orBlank { fmt(it.sideSpinRpm) },
                b.orBlank { fmt(it.backSpinRpm) },
                c.orBlank { fmt(it.clubSpeedMph) },
                c.orBlank { fmt(it.faceAngleDeg) },
                c.orBlank { fmt(it.pathDeg) },
                c.orBlank { fmt(it.attackAngleDeg) },
                w.orBlank { it.backswingStartUs.toString() },
                w.orBlank { it.downswingStartUs.toString() },
                w.orBlank { it.impactUs.toString() },
                w.orBlank { it.followThroughEndUs.toString() },
                w.orBlank { it.endRecordingUs.toString() },
                w?.tempo?.let { fmt(it) } ?: "",
                toHex(s.rawMetrics),
            ).joinToString(",")
        }

        /** Parse one CSV row. Returns null for a malformed row (caller skips it). */
        fun decode(line: String): Shot? {
            val f = line.split(',', limit = COLS)
            if (f.size != COLS) return null
            val shotId = f[0].toLongOrNull()?.toInt() ?: return null
            val shotType = when (f[1]) {
                "PRACTICE" -> R10Protos.Metrics.ShotType.PRACTICE
                "NORMAL" -> R10Protos.Metrics.ShotType.NORMAL
                else -> return null
            }
            val at = f[2].toLongOrNull() ?: return null

            val hasBall = f[3].isNotEmpty()
            val ball = if (hasBall) {
                BallDisplay(
                    ballSpeedMph = f[3].toDoubleOrZero(),
                    launchAngleDeg = f[4].toDoubleOrZero(),
                    launchDirectionDeg = f[5].toDoubleOrZero(),
                    spinAxisDeg = f[6].toDoubleOrZero(),
                    totalSpinRpm = f[7].toDoubleOrZero(),
                    sideSpinRpm = f[8].toDoubleOrZero(),
                    backSpinRpm = f[9].toDoubleOrZero(),
                )
            } else {
                null
            }

            val hasClub = f[10].isNotEmpty()
            val club = if (hasClub) {
                ClubDisplay(
                    clubSpeedMph = f[10].toDoubleOrZero(),
                    faceAngleDeg = f[11].toDoubleOrZero(),
                    pathDeg = f[12].toDoubleOrZero(),
                    attackAngleDeg = f[13].toDoubleOrZero(),
                )
            } else {
                null
            }

            val hasSwing = f[14].isNotEmpty()
            val swing = if (hasSwing) {
                SwingDisplay(
                    backswingStartUs = f[14].toLongOrZero(),
                    downswingStartUs = f[15].toLongOrZero(),
                    impactUs = f[16].toLongOrZero(),
                    followThroughEndUs = f[17].toLongOrZero(),
                    endRecordingUs = f[18].toLongOrZero(),
                    tempo = f[19].toDoubleOrNull(),
                )
            } else {
                null
            }

            return Shot(
                shotId = shotId,
                shotType = shotType,
                receivedAtMs = at,
                ball = ball,
                club = club,
                swing = swing,
                rawMetrics = fromHex(f[COL_RAW_METRICS]),
            )
        }

        private fun <T> T?.orBlank(render: (T) -> String): String = this?.let(render) ?: ""

        /** Round-trip-safe decimal format: no scientific notation, no trailing zeros. */
        private fun fmt(v: Double): String {
            if (v == v.toLong().toDouble()) return v.toLong().toString()
            return v.toString()
        }

        private fun String.toDoubleOrZero(): Double = toDoubleOrNull() ?: 0.0
        private fun String.toLongOrZero(): Long = toLongOrNull() ?: 0L

        private val HEX = "0123456789ABCDEF".toCharArray()
        private const val HEX_RADIX = 16
        private const val BITS_PER_NIBBLE = 4
        private const val NIBBLE_MASK = 0x0F
        private const val BYTE_MASK = 0xFF

        fun toHex(bytes: ByteArray): String {
            val out = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and BYTE_MASK
                out.append(HEX[v ushr BITS_PER_NIBBLE]).append(HEX[v and NIBBLE_MASK])
            }
            return out.toString()
        }

        fun fromHex(hex: String): ByteArray {
            if (hex.isEmpty()) return ByteArray(0)
            if (hex.length % 2 != 0) return ByteArray(0)
            val out = ByteArray(hex.length / 2)
            for (i in out.indices) {
                val hi = Character.digit(hex[i * 2], HEX_RADIX) shl BITS_PER_NIBBLE
                val lo = Character.digit(hex[i * 2 + 1], HEX_RADIX)
                out[i] = (hi + lo).toByte()
            }
            return out
        }
    }
}

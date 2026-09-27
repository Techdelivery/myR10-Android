package com.techdelivery.r10.data

import com.techdelivery.r10.club.GolfClub
import com.techdelivery.r10.data.ShotCsvFormat.COL_RAW_METRICS_V1
import com.techdelivery.r10.data.ShotCsvFormat.COL_SHOT_ID
import com.techdelivery.r10.data.ShotCsvFormat.HEADER
import com.techdelivery.r10.data.ShotCsvFormat.HEADER_V1
import com.techdelivery.r10.data.ShotCsvFormat.RECENT_KEY_WINDOW
import com.techdelivery.r10.data.ShotCsvFormat.SCHEMA_VERSION_COLS
import com.techdelivery.r10.data.ShotCsvFormat.V1_COLS
import com.techdelivery.r10.data.ShotCsvFormat.decode
import com.techdelivery.r10.data.ShotCsvFormat.encode
import com.techdelivery.r10.data.ShotCsvFormat.toHex
import com.techdelivery.r10.data.ShotCsvFormat.upgradeRowToV2
import com.techdelivery.r10.data.ShotCsvFormat.validateText
import com.techdelivery.r10.protocol.shot.Shot
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile

/**
 * Shot-history persistence (DESIGN §8 `Shot` entity, DESIGN §11 M3).
 *
 * Owns the file, the lock and the durability rules. The three collaborators are
 * deliberately narrow:
 *  - [ShotCsvFormat] — schema, encode/decode, validation. No filesystem.
 *  - [ShotDedupIndex] — the bounded LRU of "already stored" payload keys.
 *  - [ShotCsvRewriter] — atomic whole-file rewrites (club edit, delete, migrate).
 *
 * Every mutation is `read → apply → temp file → rename`, so a process kill leaves
 * either the previous file or the complete new one. The store is the only writer:
 * the UI never touches the file, which is what keeps the mutability from spreading.
 *
 * DESIGN names Room. This build uses a CSV store instead, for two reasons recorded
 * in DESIGN §8:
 *  - KSP has no release for the Kotlin version this project pins (2.4.x compiler),
 *    and the kapt route was rejected on build-memory grounds that depend on the host
 *    rather than on the project.
 *  - The persisted shape is flat, numeric, and app-owned, so CSV is lossless here —
 *    and it doubles as the M3 CSV export with no second serializer.
 *
 * All fields are numbers, enum names, or hex, so no CSV quoting/escaping is needed —
 * that is deliberate.
 *
 * Thread-safe: one [Mutex] guards the file.
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
    private val dedup = ShotDedupIndex(recentKeyWindow)

    /** Atomic row-rewriting engine, shared by every mutation. */
    private val rewriter = ShotCsvRewriter(file)
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
        if (key != null && dedup.contains(key)) return@withLock false
        val needsHeader = !file.exists() || file.length() == 0L
        appendDurable((if (needsHeader) "$HEADER\n" else "") + encode(shot) + "\n")
        if (key != null) dedup.add(key)
        true
    }

    /** Bulk append, skipping anything already stored. */
    suspend fun appendAll(shots: List<Shot>) = mutex.withLock {
        ensureIndexedUnlocked()
        val sb = StringBuilder()
        var wrote = 0
        shots.forEach {
            val key = dedupKey(it)
            if (key != null && dedup.contains(key)) return@forEach
            if (wrote == 0 && (!file.exists() || file.length() == 0L)) sb.append(HEADER).append('\n')
            sb.append(encode(it)).append('\n')
            wrote++
            if (key != null) dedup.add(key)
        }
        if (wrote > 0) appendDurable(sb.toString())
    }

    /** Oldest first, as stored. Corrupt lines are skipped, never fatal. */
    suspend fun loadAll(): List<Shot> = mutex.withLock { readUnlocked() }

    suspend fun clear() = mutex.withLock {
        runCatching { file.delete() }
        // Reset the index and force a re-read: if the delete failed the surviving
        // rows must still be known to the dedup set.
        dedup.clear()
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
     * Set or clear the club annotation on one stored shot (ROADMAP R5).
     *
     * Edits the row's `club_label` field in place and leaves every other byte of
     * every other row untouched, so a row this build cannot decode (or a club label
     * a later version adds) survives an edit it did not understand.
     *
     * Returns true when a row with that [shotId] existed. A missing id is a no-op,
     * not an error: the UI and the file can legitimately disagree after a delete.
     */
    suspend fun updateClub(shotId: Int, club: GolfClub?): Boolean = mutex.withLock {
        val target = club?.id.orEmpty()
        val changed = rewriter.rewrite(transform = { line -> setClubLabel(line, shotId, target) })
        // The index caches what the file contains. Left stale after a rewrite, a
        // deleted row's key would suppress the device re-pushing that shot for the
        // rest of the session — silent loss that looks like "the R10 stopped sending".
        if (changed) rebuildIndexUnlocked()
        changed
    }

    /**
     * Delete one stored shot (ROADMAP R6).
     *
     * Irreversible in-app: an export is a copy, and that copy is the only way back.
     * The UI confirms before calling this, naming the row it is about to lose.
     *
     * Returns true when a row was removed. The dedup index is rebuilt from the
     * surviving rows, so a device that re-pushes a deleted shot writes it again
     * instead of being silently suppressed by a stale key.
     */
    suspend fun deleteShot(shotId: Int): Boolean = mutex.withLock {
        val changed = rewriter.rewrite(transform = { line -> if (shotIdOf(line) == shotId) null else line })
        if (changed) rebuildIndexUnlocked()
        changed
    }

    /**
     * Upgrade a v1 file to the current schema (DESIGN §8, ROADMAP R5).
     *
     * Explicit rather than implicit inside `decode`: reading a file must never
     * rewrite it. Returns true when a migration actually ran.
     *
     * Each row is re-emitted as current-version, so `club_label` starts empty —
     * there is no way to know which club an old shot was hit with.
     */
    suspend fun migrate(): Boolean = mutex.withLock {
        if (!file.exists() || file.length() == 0L) return@withLock false
        val lines = file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val header = lines.firstOrNull() ?: return@withLock false
        if (header == HEADER) return@withLock false
        if (header != HEADER_V1) return@withLock false
        // A row this build cannot decode is carried over verbatim rather than dropped:
        // migration must never be the thing that loses a shot. A v1 row under the v2
        // header is still readable — decode detects the shape.
        rewriter.rewrite({ line -> decode(line)?.let { encode(it) } ?: line }, header = HEADER)
        rebuildIndexUnlocked()
        true
    }

    /**
     * Replace [clubLabel] in the row for [shotId], leaving every other field byte
     * identical. Unknown row shapes come back unchanged, not corrupted.
     */
    private fun setClubLabel(line: String, shotId: Int, clubLabel: String): String? {
        if (shotIdOf(line) != shotId) return line
        val f = line.split(',', limit = SCHEMA_VERSION_COLS)
        return when {
            f.size == SCHEMA_VERSION_COLS -> f.dropLast(1).plus(clubLabel).joinToString(",")
            f.size == V1_COLS -> upgradeRowToV2(line, clubLabel)
            else -> line
        }
    }

    /**
     * The `shot_id` of a raw row, whatever its schema version. Column 0 in v1, but
     * column 1 at the current version because `schema_version` leads — getting this
     * wrong would edit or delete *every* row instead of one.
     */
    private fun shotIdOf(line: String): Int? {
        val f = line.split(',', limit = SCHEMA_VERSION_COLS)
        return when {
            f.size == SCHEMA_VERSION_COLS -> f[COL_SHOT_ID].trim().toIntOrNull()
            f.size == V1_COLS -> f[0].trim().toIntOrNull()
            else -> null
        }
    }

    private fun rebuildIndexUnlocked() {
        indexed = true
        dedup.seedFrom(readUnlocked(), ::dedupKey)
    }

    /**
     * Validate the live store (ROADMAP R4).
     *
     * The export path used to trust the file: `exportSnapshot` copied it and
     * reported a byte count, while `decode` silently skipped malformed rows — so a
     * torn or truncated file exported "successfully" with rows quietly missing.
     * Validation makes that visible instead.
     */
    suspend fun validate(): CsvValidation = mutex.withLock { validateText(snapshotText()) }

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
        rebuildIndexUnlocked()
    }

    /** Non-locking snapshot — callers must already hold [mutex]. */
    private fun snapshotText(): String = if (!file.exists()) "$HEADER\n" else file.readText()

    private fun readUnlocked(): List<Shot> {
        if (!file.exists()) return emptyList()
        return file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != HEADER && it != HEADER_V1 }
            .mapNotNull { decode(it) }
    }
}

/**
 * Result of a CSV validation pass (ROADMAP R4).
 *
 * Exists because `decode` deliberately skips malformed rows: without an explicit
 * check, a torn file exports as a success with rows silently missing.
 */
data class CsvValidation(val headerOk: Boolean, val dataRows: Int, val parsed: Int, val problems: List<String>) {
    val isClean: Boolean get() = headerOk && problems.isEmpty()

    /** One-line human summary for the Export button / logs. */
    fun summary(): String {
        if (isClean) return "$parsed row${if (parsed == 1) "" else "s"} OK"
        val shown = problems.take(MAX_REPORTED)
        val more = if (problems.size > shown.size) " (+${problems.size - shown.size} more)" else ""
        return "$parsed/$dataRows rows OK · ${problems.size} problem(s): ${shown.joinToString("; ")}$more"
    }

    private companion object {
        const val MAX_REPORTED = 5
    }
}

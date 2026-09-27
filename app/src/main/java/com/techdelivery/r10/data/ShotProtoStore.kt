package com.techdelivery.r10.data

import LaunchMonitor.Proto.R10Protos
import com.techdelivery.r10.club.GolfClub
import com.techdelivery.r10.protocol.shot.Shot
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Shot history in the R10's own protobuf (DESIGN §8, ROADMAP R7).
 *
 * **What is stored is what the device sent.** A record is a [R10Protos.StoredShot]
 * wrapping the untouched `Metrics` message, plus the two facts only this app knows:
 * arrival time, and the club the user picked. The display values the old CSV held
 * (mph, rpm, degrees) are *recomputed* on load by [MetricConverter] — the same
 * conversion that ran when the shot arrived. So the store cannot lose precision to
 * text formatting, and it needs no schema machinery: protobuf's `optional` fields
 * already mean "unset", so a field added later reads as absent without a version
 * bump or a migration.
 *
 * **Framing: length-delimited records in one file** (`shots.bin`), protobuf's own
 * stream encoding — a varint length prefix per record, which is what
 * `parseDelimitedFrom` reads. The alternative, one message with a `repeated`
 * field, is also pure proto but costs three things this design depends on:
 *  - every new shot would rewrite the whole file, giving up the one-fsync append
 *    that makes a shot durable in a single write;
 *  - one bad byte would make the entire file unparseable, so damage anywhere costs
 *    the whole session, instead of costing the records around it;
 *  - a truncated tail could not be told apart from general corruption.
 *
 * A record is a shot or the header. Damaged records are identified and reported,
 * the rest are kept: losing a session to one torn tail is not acceptable when the
 * damage is local.
 *
 * Dedup is [ShotDedupIndex] on the raw `Metrics` bytes plus the shot id — the same
 * key the CSV store used, for the same reason (the R10 restarts its id sequence on
 * every power cycle, so `shot_id` alone is not identity).
 *
 * Thread-safe: one [Mutex] guards the file.
 */
class ShotProtoStore(
    private val file: File,
    /**
     * How many recent dedup keys to keep. A constructor parameter so LRU eviction
     * is testable without writing thousands of records.
     */
    private val recentKeyWindow: Int = RECENT_KEY_WINDOW,
) {

    init {
        require(recentKeyWindow >= 1) { "recentKeyWindow must be at least 1" }
    }

    private val mutex = Mutex()

    private val dedup = ShotDedupIndex(recentKeyWindow)
    private var indexed = false

    companion object {
        /**
         * Bumped only if a change makes older files unreadable. Protobuf itself
         * handles additive change, so this exists for the rare destructive one —
         * and to let a future reader refuse a file it cannot interpret rather than
         * guess.
         */
        const val STORE_FORMAT_VERSION = 1

        /** Bounded LRU of stored-payload keys; see [ShotDedupIndex]. */
        const val RECENT_KEY_WINDOW = 2_000

        /** Records read from one file. Bounded so a corrupt length cannot allocate forever. */
        private const val MAX_RECORDS = 200_000
    }

    /**
     * Append one shot. Returns true when written, false when that exact payload is
     * already stored.
     */
    suspend fun append(shot: Shot): Boolean = mutex.withLock {
        ensureIndexedUnlocked()
        val key = dedupKey(shot)
        if (key != null && dedup.contains(key)) return@withLock false
        val record = encodeRecord(shot)
        // Header + record in one durable write, so a shot is never stored in a file
        // with no header (which a reader would have to guess at).
        val needsHeader = !file.exists() || file.length() == 0L
        appendDurable(if (needsHeader) headerBytes() + record else record)
        if (key != null) dedup.add(key)
        true
    }

    /** Bulk append, skipping anything already stored. One durable write for the lot. */
    suspend fun appendAll(shots: List<Shot>) = mutex.withLock {
        ensureIndexedUnlocked()
        val pending = ArrayList<ByteArray>(shots.size)
        shots.forEach { shot ->
            val key = dedupKey(shot)
            if (key != null && dedup.contains(key)) return@forEach
            pending.add(encodeRecord(shot))
            if (key != null) dedup.add(key)
        }
        if (pending.isEmpty()) return@withLock
        val needsHeader = !file.exists() || file.length() == 0L
        val parts = if (needsHeader) listOf(headerBytes()) + pending else pending
        appendDurable(parts.reduce { a, b -> a + b })
    }

    /** Oldest first, as stored. Damaged records are skipped, never fatal. */
    suspend fun loadAll(): List<Shot> = mutex.withLock { readShotsUnlocked() }

    /**
     * Set or clear the club annotation on one stored shot.
     *
     * Rewrites the file: the store holds the device's bytes, so changing a label
     * means re-emitting that one record. Returns true when the shot existed. A
     * missing id is a no-op, not an error.
     *
     * Returns **false without writing** when the file is damaged (see
     * [readRecordsUnlocked]). A rewrite writes back only the records that were
     * readable, so running one over a torn tail would silently delete everything
     * after the tear — turning one damaged record into a lost session. [validate]
     * names the damage and the export surfaces it.
     */
    suspend fun updateClub(shotId: Int, club: GolfClub?): Boolean = mutex.withLock {
        val scan = readRecordsUnlocked()
        if (scan.damaged) return@withLock false
        val records = scan.records
        var changed = false
        val out = ArrayList<ByteArray>(records.size)
        for (r in records) {
            val shot = r.toShot() ?: run {
                out.add(r)
                continue
            }
            if (shot.shotId != shotId) {
                out.add(r)
            } else {
                out.add(encodeRecord(shot.copy(clubLabel = club?.id)))
                changed = true
            }
        }
        if (changed) writeAllUnlocked(out)
        changed
    }

    /**
     * Delete one stored shot.
     *
     * Irreversible in-app: an export is a copy, and that copy is the only way back.
     * The UI confirms before calling this. The dedup index is rebuilt from the
     * surviving records, so a device that re-pushes a deleted shot writes it again
     * instead of being suppressed by a stale key.
     *
     * Returns **false without writing** when the file is damaged, for the same
     * reason as [updateClub]: a rewrite must never be the thing that drops records
     * past a tear.
     */
    suspend fun deleteShot(shotId: Int): Boolean = mutex.withLock {
        val scan = readRecordsUnlocked()
        if (scan.damaged) return@withLock false
        val records = scan.records
        val out = records.filter { r -> r.toShot()?.shotId != shotId }
        if (out.size == records.size) return@withLock false
        writeAllUnlocked(out)
        dedup.seedFrom(out.mapNotNull { it.toShot() }, ::dedupKey)
        true
    }

    /**
     * Write a CSV copy of the history (DESIGN §11 M3 export, ROADMAP R4 validation).
     *
     * The CSV is a **derived view**, not the storage format: the store holds the
     * device's own bytes, and the human-readable form is produced on the way out.
     * That is the whole reason this store can be lossless and the export can still
     * be something a person opens in a spreadsheet.
     */
    suspend fun exportCsv(dir: File, stamp: String): File = mutex.withLock {
        dir.mkdirs()
        val out = File(dir, "r10-shots-$stamp.csv")
        val sb = StringBuilder(ShotCsvFormat.HEADER).append('\n')
        readShotsUnlocked().forEach { sb.append(ShotCsvFormat.encode(it)).append('\n') }
        out.writeText(sb.toString())
        out
    }

    /**
     * Check the live store: header present, every record parses, every record has
     * metrics. Damage is reported per record index rather than swallowed.
     */
    suspend fun validate(): ShotStoreValidation = mutex.withLock {
        val problems = mutableListOf<String>()
        if (!file.exists() || file.length() == 0L) {
            return@withLock ShotStoreValidation(false, 0, listOf("no shot history yet"))
        }
        val scan = readRecordsUnlocked(collectProblems = problems)
        val records = scan.records
        val header = records.firstOrNull()?.toHeader()
        if (header == null) {
            problems += "record 1 is not a header record"
        } else if (header.storeFormatVersion != STORE_FORMAT_VERSION) {
            problems += "store_format_version ${header.storeFormatVersion} " +
                "is not the supported version $STORE_FORMAT_VERSION"
        }
        // readRecordsUnlocked already reported and skipped anything that is not a
        // record, so what is left is the header plus shots. Counting the survivors
        // matters: a torn tail must not read as "no history".
        val shots = records.mapNotNull { it.toShot() }
        return@withLock ShotStoreValidation(header != null, shots.size, problems)
    }

    /** Delete all history. */
    suspend fun clear() = mutex.withLock {
        runCatching { file.delete() }
        dedup.clear()
        indexed = false
    }

    // --- internals (mutex held) ---

    /**
     * Content key for cross-session dedup, or null when the shot carries no metrics.
     *
     * `shot_id` is folded in even though the metrics already carry it, so a shot
     * whose id differs is never dropped on a payload collision.
     */
    private fun dedupKey(shot: Shot): String? = shot.rawMetrics.takeIf { it.isNotEmpty() }
        ?.let { "${shot.shotId}|${ShotCsvFormat.toHex(it)}" }

    private fun ensureIndexedUnlocked() {
        if (indexed) return
        indexed = true
        dedup.seedFrom(readShotsUnlocked(), ::dedupKey)
    }

    /**
     * Every shot in the file, oldest first. The header record is not a shot and is
     * dropped by [ByteArray.toShot] returning null for it, so this does not depend
     * on the header being first — only on it being unshot-like.
     */
    private fun readShotsUnlocked(): List<Shot> = readRecordsUnlocked().records.mapNotNull { it.toShot() }

    /**
     * Every record — the header included — as framed bytes, and whether the file
     * holds damage a rewrite must not paper over.
     *
     * A record that cannot be framed or read is reported (when asked) and the walk
     * stops there: past an unreadable length prefix the offsets are no longer
     * trustworthy, and the bytes after it are not a store we can honestly read. The
     * records before that point are kept, because a torn tail must not cost the
     * session that came before it.
     *
     * `damaged` answers "may this file be rewritten?". It is set when the walk
     * stopped early, when the record cap cut the file short, or when a record was
     * neither a header nor a shot. [updateClub] and [deleteShot] rewrite by writing
     * the readable records back verbatim, so in any of those states the rewrite
     * would drop exactly the records the store exists to keep — and they refuse
     * rather than do it. The damaged bytes are left on disk for [validate] to
     * report and the user to export; carrying them along would mean inventing
     * record boundaries, which is how one torn tail becomes a whole lost session.
     */
    private fun readRecordsUnlocked(collectProblems: MutableList<String> = mutableListOf()): RecordScan {
        if (!file.exists() || file.length() == 0L) return RecordScan(emptyList(), false)
        val bytes = file.readBytes()
        val framed = DelimitedRecords.read(bytes) { index, message ->
            collectProblems += "record ${index + 1}: $message"
        }
        // Bytes the walk could not account for are the tail it stopped before.
        val stoppedEarly = framed.sumOf { it.size } < bytes.size
        val capped = framed.size > MAX_RECORDS
        if (capped) {
            collectProblems += "file holds more than $MAX_RECORDS records; the rest were not read"
        }
        val within = framed.take(MAX_RECORDS)
        val records = within.filterIndexed { index, record ->
            val ok = isRecord(record)
            if (!ok) collectProblems += "record ${index + 1}: not a header or a shot"
            ok
        }
        return RecordScan(records, stoppedEarly || capped || records.size != within.size)
    }

    /**
     * True when a record body is a header or a shot. Anything else is damage.
     *
     * "Parses" is not enough on its own: protobuf is permissive, and arbitrary bytes
     * often decode as a message carrying nothing but unknown fields. So a shot must
     * actually hold metrics, and a header must carry the version — otherwise a
     * corrupt record would sail through as a valid empty one.
     */
    private fun isRecord(framed: ByteArray): Boolean {
        if (framed.toHeader()?.hasStoreFormatVersion() == true) return true
        return framed.toShot() != null
    }

    /** The shot a framed record holds, or null when it is not a shot record. */
    private fun ByteArray.toShot(): Shot? = toStoredShot()?.let { ShotRecordCodec.toShot(it) }

    /** The header a framed record holds, or null. */
    private fun ByteArray.toHeader(): R10Protos.ShotLogHeader? = DelimitedRecords.body(this)
        ?.let { runCatching { R10Protos.ShotLogHeader.parseFrom(it) }.getOrNull() }

    /**
     * Replace the file atomically: temp file, fsync, rename.
     *
     * A process kill therefore leaves either the previous file or the complete new
     * one. Rewriting the whole log is the price of editing one record in a file
     * that is otherwise append-only, and it is the same price the CSV store paid.
     *
     * [records] are **framed** bytes read back from the file, so writing them
     * verbatim cannot drop a field this build does not know.
     */
    private fun writeAllUnlocked(records: List<ByteArray>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        val out = FileOutputStream(tmp)
        out.use { stream ->
            records.forEach { stream.write(it) }
            stream.flush()
            stream.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            // Rename can fail when the destination exists on some filesystems, so
            // fall back to delete-then-rename before giving up.
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IOException("could not replace ${file.absolutePath}")
            }
        }
    }

    private fun headerBytes(): ByteArray = ShotRecordCodec.header(STORE_FORMAT_VERSION)

    private fun encodeRecord(shot: Shot): ByteArray = ShotRecordCodec.encode(shot)

    /** One durable append, so a shot is either whole on disk or absent. */
    private fun appendDurable(bytes: ByteArray) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rwd").use { raf ->
            raf.seek(raf.length())
            raf.write(bytes)
            raf.fd.sync()
        }
    }
}

/**
 * What one read of the file yielded: the records it could account for, and whether
 * the file holds damage that makes a whole-file rewrite unsafe.
 */
private class RecordScan(
    /** Framed bytes, header included, oldest first. */
    val records: List<ByteArray>,
    /** True when rewriting these records would drop records the read could not see. */
    val damaged: Boolean,
)

/**
 * Health of the shot log (ROADMAP R4's question, asked of the store rather than of
 * an export): is the header there, and does every record parse?
 *
 * Damage is reported per record and the surviving records are still counted, because
 * a torn tail must not read as "no history" — that is the failure that loses a
 * session quietly.
 */
data class ShotStoreValidation(val headerOk: Boolean, val records: Int, val problems: List<String>) {
    val isClean: Boolean get() = headerOk && problems.isEmpty()
}

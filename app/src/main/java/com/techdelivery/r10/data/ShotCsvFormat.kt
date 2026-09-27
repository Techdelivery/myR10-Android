package com.techdelivery.r10.data

import LaunchMonitor.Proto.R10Protos
import com.techdelivery.r10.club.GolfClub
import com.techdelivery.r10.protocol.shot.BallDisplay
import com.techdelivery.r10.protocol.shot.ClubDisplay
import com.techdelivery.r10.protocol.shot.Shot
import com.techdelivery.r10.protocol.shot.SwingDisplay
import java.io.File

/**
 * The shot CSV *format*: schema, encode/decode, and validation (DESIGN §8).
 *
 * Split out of [ShotProtoStore] because none of it touches the filesystem. The store
 * owns the file, the mutex, the dedup index and the atomic rewrite; this owns the
 * bytes' meaning. Both are needed to make sense of a row, and keeping them apart is
 * what lets the store stay about durability and the format about compatibility.
 *
 * The rule everything here serves (ROADMAP R4, extended in R5): a file this app
 * wrote earlier must keep loading **and** keep validating clean. A v1 file is not
 * an old version to be tolerated reluctantly — it is data the user still has.
 */
object ShotCsvFormat {
    /**
     * Current on-disk schema version (DESIGN §8, ROADMAP R5).
     *
     * v1 — 21 columns, no club annotation.
     * v2 — `schema_version` leading column plus a trailing `club_label`.
     *
     * The version is a **column**, not a comment line: a comment would be
     * dropped by the R4 header check and by any external reader, while a column
     * travels with the data.
     */
    const val SCHEMA_VERSION = 2

    /** Columns in the original 21-column layout, kept so v1 files stay describable. */
    val COL_NAMES_V1: List<String> = listOf(
        "shot_id", "shot_type", "received_at_ms",
        "ball_speed_mph", "launch_angle_deg", "launch_direction_deg",
        "spin_axis_deg", "total_spin_rpm", "side_spin_rpm", "back_spin_rpm",
        "club_speed_mph", "face_angle_deg", "path_deg", "attack_angle_deg",
        "backswing_start_us", "downswing_start_us", "impact_us",
        "follow_through_end_us", "end_recording_us", "tempo",
        "raw_metrics_hex",
    )

    /**
     * Column order, single source of truth for [HEADER] and for validation
     * messages. The version column leads and the user-entered club label trails.
     */
    val COL_NAMES: List<String> = buildList {
        add("schema_version")
        addAll(COL_NAMES_V1)
        add("club_label")
    }

    val HEADER: String = COL_NAMES.joinToString(",")
    val HEADER_V1: String = COL_NAMES_V1.joinToString(",")

    /** Column count per schema version, for messages and tests. */
    const val V1_COLS = 21
    val SCHEMA_VERSION_COLS = COL_NAMES.size

    /** Index of the trailing `raw_metrics_hex` column, per schema version. */
    const val COL_RAW_METRICS_V1 = 20
    private val COL_RAW_METRICS = SCHEMA_VERSION_COLS - 2
    const val COL_SHOT_ID = 1

    /** Measurement columns parsed as a double, in v1 coordinates. */
    private val DOUBLE_COLUMNS = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 19)

    /** Swing-timing columns parsed as a long, in v1 coordinates. */
    private val LONG_COLUMNS = intArrayOf(14, 15, 16, 17, 18)

    /** Hex encodes one byte as two characters. */
    private const val HEX_CHARS_PER_BYTE = 2

    /** Characters accepted in the `raw_metrics_hex` column (either case). */
    private const val HEX_VALID = "0123456789abcdefABCDEF"

    /**
     * Encode a shot at the current schema version: the version, the 21 v1
     * columns, then the user-entered club label.
     */
    fun encode(s: Shot): String = buildString {
        append(SCHEMA_VERSION).append(',')
        append(encodeV1Fields(s))
        append(',').append(s.clubLabel.orEmpty().take(GolfClub.ID_MAX_LEN))
    }

    /** The 21 original fields, in the v1 order, without any version or club column. */
    private fun encodeV1Fields(s: Shot): String {
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

    /**
     * Which schema a raw row was written with, from its **shape**: a 22-field
     * row is current-version, a 21-field row is v1. Deliberately does not trust
     * the version cell — a row with a bogus version should be reported by
     * `checkRow` with a useful message, not rejected as the wrong shape.
     *
     * The field count is the **true** one. Splitting with a limit would read a
     * row wider than this schema as current-version, with the overflow hiding
     * inside `club_label`: the row would decode, and `validateText` would call it
     * clean, which is a newer build's extra column silently dropped from the
     * user's history rather than reported.
     */
    private fun rowVersion(line: String): Int? {
        val fields = line.count { it == ',' } + 1
        return when (fields) {
            SCHEMA_VERSION_COLS -> SCHEMA_VERSION
            V1_COLS -> 1
            else -> null
        }
    }

    /**
     * Parse one CSV row at any known schema version. Returns null for a
     * malformed row (caller skips it).
     *
     * A v1 file must keep loading forever: R4's rule is that a previously
     * exported file never becomes "invalid", so version detection is by shape,
     * not by a flag the file is required to carry.
     */
    fun decode(line: String): Shot? = when (rowVersion(line)) {
        SCHEMA_VERSION -> decodeRow(line.split(',', limit = SCHEMA_VERSION_COLS), offset = 1, club = true)
        1 -> decodeRow(line.split(',', limit = V1_COLS), offset = 0, club = false)
        else -> null
    }

    private fun decodeRow(f: List<String>, offset: Int, club: Boolean): Shot? {
        fun c(i: Int) = f[i + offset]
        val shotId = c(0).toLongOrNull()?.toInt() ?: return null
        val shotType = when (c(1)) {
            "PRACTICE" -> R10Protos.Metrics.ShotType.PRACTICE
            "NORMAL" -> R10Protos.Metrics.ShotType.NORMAL
            else -> return null
        }
        val at = c(2).toLongOrNull() ?: return null

        val hasBall = c(3).isNotEmpty()
        val ball = if (hasBall) {
            BallDisplay(
                ballSpeedMph = c(3).toDoubleOrZero(),
                launchAngleDeg = c(4).toDoubleOrZero(),
                launchDirectionDeg = c(5).toDoubleOrZero(),
                spinAxisDeg = c(6).toDoubleOrZero(),
                totalSpinRpm = c(7).toDoubleOrZero(),
                sideSpinRpm = c(8).toDoubleOrZero(),
                backSpinRpm = c(9).toDoubleOrZero(),
            )
        } else {
            null
        }

        val hasClub = c(10).isNotEmpty()
        val clubDisplay = if (hasClub) {
            ClubDisplay(
                clubSpeedMph = c(10).toDoubleOrZero(),
                faceAngleDeg = c(11).toDoubleOrZero(),
                pathDeg = c(12).toDoubleOrZero(),
                attackAngleDeg = c(13).toDoubleOrZero(),
            )
        } else {
            null
        }

        val hasSwing = c(14).isNotEmpty()
        val swing = if (hasSwing) {
            SwingDisplay(
                backswingStartUs = c(14).toLongOrZero(),
                downswingStartUs = c(15).toLongOrZero(),
                impactUs = c(16).toLongOrZero(),
                followThroughEndUs = c(17).toLongOrZero(),
                endRecordingUs = c(18).toLongOrZero(),
                tempo = c(19).toDoubleOrNull(),
            )
        } else {
            null
        }

        return Shot(
            shotId = shotId,
            shotType = shotType,
            receivedAtMs = at,
            ball = ball,
            club = clubDisplay,
            swing = swing,
            rawMetrics = fromHex(f[if (club) COL_RAW_METRICS else COL_RAW_METRICS_V1]),
            clubLabel = if (club) f[SCHEMA_VERSION_COLS - 1].ifBlank { null } else null,
        )
    }

    /**
     * Validate CSV text: header, then every data row. Line numbers are 1-based
     * against the original text so they match what an editor shows.
     *
     * Both headers are accepted. A v1 file must validate clean, not merely
     * load — an export of an old file reporting "problems" would be a false
     * alarm about the user's own history.
     */
    fun validateText(text: String): CsvValidation {
        val problems = mutableListOf<String>()
        var headerOk = false
        var sawHeader = false
        var dataRows = 0
        var parsed = 0

        text.split('\n').forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachIndexed
            if (!sawHeader) {
                sawHeader = true
                headerOk = line == HEADER || line == HEADER_V1
                if (!headerOk) {
                    problems += "line ${index + 1}: header mismatch " +
                        "(expected the ${COL_NAMES.size}-column v$SCHEMA_VERSION header, " +
                        "or the ${COL_NAMES_V1.size}-column v1 header)"
                }
                return@forEachIndexed
            }
            dataRows++
            val rowErrors = checkRow(line)
            if (rowErrors.isEmpty() && decode(line) != null) {
                parsed++
            } else {
                problems += rowErrors.ifEmpty { listOf("row does not decode") }
                    .map { "line ${index + 1}: $it" }
            }
        }

        if (!sawHeader) problems += "file is empty"
        return CsvValidation(headerOk, dataRows, parsed, problems)
    }

    /**
     * Row-level checks behind [decode]'s "skip it" behaviour. A bare "invalid
     * file" is undiagnosable in a bug report; "bad hex in raw_metrics_hex" is
     * actionable (ROADMAP R4).
     *
     * Checks are per schema version: a v1 row is validated against the v1
     * columns and cannot be blamed for lacking a `club_label` that version
     * never had.
     */
    private fun checkRow(line: String): List<String> {
        val version = rowVersion(line)
            ?: return listOf(
                "expected $SCHEMA_VERSION_COLS (v$SCHEMA_VERSION) " +
                    "or $V1_COLS (v1) fields, got ${line.split(',').size}",
            )
        val offset = if (version == SCHEMA_VERSION) 1 else 0
        val expected = if (offset == 1) SCHEMA_VERSION_COLS else V1_COLS
        val f = line.split(',', limit = expected)
        if (f.size != expected) return listOf("expected $expected fields, got ${f.size}")
        return checkDataFields(f, offset) + checkVersionCell(f, version)
    }

    /**
     * Validate an arbitrary CSV file on disk, without touching a store.
     *
     * Lives with the format rather than the store because it needs no lock and no
     * history: it answers "is this file sound", which is a question about the bytes,
     * not about the app's copy of them.
     */
    fun validateFile(file: File): CsvValidation = if (!file.exists()) {
        CsvValidation(false, 0, 0, listOf("file does not exist: ${file.absolutePath}"))
    } else {
        validateText(file.readText())
    }

    /**
     * Validate the 21 measurement columns.
     *
     * Addressed in **v1 coordinates** — [offset] skips the leading
     * `schema_version` cell — so a message names the v1 column name. Indexing the
     * current list directly would report `ball_speed_mph` for a bad
     * `launch_angle_deg`, and a wrong-column message is worse than none.
     */
    private fun checkDataFields(f: List<String>, offset: Int): List<String> {
        val errs = mutableListOf<String>()
        fun name(i: Int) = COL_NAMES_V1[i]
        fun c(i: Int) = f[i + offset]

        if (c(0).toLongOrNull() == null) errs += "${name(0)} '${c(0)}' is not an integer"
        if (c(1) != "PRACTICE" && c(1) != "NORMAL") {
            errs += "${name(1)} '${c(1)}' is not PRACTICE or NORMAL"
        }
        if (c(2).toLongOrNull() == null) errs += "${name(2)} '${c(2)}' is not an integer"

        DOUBLE_COLUMNS.forEach { i ->
            if (c(i).isNotEmpty() && c(i).toDoubleOrNull() == null) {
                errs += "${name(i)} '${c(i)}' is not a number"
            }
        }
        LONG_COLUMNS.forEach { i ->
            if (c(i).isNotEmpty() && c(i).toLongOrNull() == null) {
                errs += "${name(i)} '${c(i)}' is not an integer"
            }
        }
        hexError(f[COL_RAW_METRICS_V1 + offset], name(COL_RAW_METRICS_V1))?.let { errs += it }
        return errs
    }

    /**
     * Validate the current-version cells: the version itself, and — deliberately
     * not — the club label.
     *
     * An unrecognised `club_label` is accepted. It is still a label the user set,
     * from a newer club list or a hand-edited file, and reporting it would make a
     * previously exported file look broken for a cosmetic reason. A null from
     * `GolfClub.fromId` is what the picker and the detail card handle instead.
     */
    private fun checkVersionCell(f: List<String>, version: Int): List<String> {
        if (version != SCHEMA_VERSION) return emptyList()
        if (f[0].toIntOrNull() == SCHEMA_VERSION) return emptyList()
        return listOf("schema_version '${f[0]}' is not the supported version $SCHEMA_VERSION")
    }

    /**
     * Validate the `raw_metrics_hex` column: even length (a cut byte means a
     * torn row) and hex-only. Returns the problem message, or null when the
     * field is blank or well-formed.
     */
    private fun hexError(hex: String, columnName: String): String? {
        if (hex.isEmpty()) return null
        if (hex.length % HEX_CHARS_PER_BYTE != 0) {
            return "$columnName has odd length ${hex.length} (truncated row?)"
        }
        if (hex.any { it !in HEX_VALID }) {
            return "$columnName contains non-hex characters"
        }
        return null
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

/**
 * Result of a CSV validation pass (ROADMAP R4).
 *
 * Exists because `decode` deliberately skips malformed rows: without an explicit
 * check, a torn file exports as a success with rows silently missing. The CSV is a
 * derived view (ROADMAP R7), so this validates the export, not the store.
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

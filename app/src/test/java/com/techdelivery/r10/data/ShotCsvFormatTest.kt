package com.techdelivery.r10.data

import LaunchMonitor.Proto.R10Protos
import com.techdelivery.r10.club.GolfClub
import com.techdelivery.r10.protocol.shot.BallDisplay
import com.techdelivery.r10.protocol.shot.ClubDisplay
import com.techdelivery.r10.protocol.shot.MetricConverter
import com.techdelivery.r10.protocol.shot.Shot
import com.techdelivery.r10.protocol.shot.SwingDisplay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

/**
 * ROADMAP R4 — the shot **CSV format**: schema, encode/decode, and validation.
 *
 * The CSV is no longer the store (ROADMAP R7 moved persistence to protobuf) but it
 * is still what the user takes off the device: `MainActivity.exportCsv` writes it
 * with `ShotProtoStore.exportCsv` and immediately hands the file to
 * [ShotCsvFormat.validateFile]. So the R4 rules still decide whether an export is
 * a success or a lie: a file this app wrote earlier must keep loading **and** keep
 * validating clean, and damage must be reported per line rather than swallowed.
 *
 * Rows are corrupted **by column name** ([withColumn]), never by index. An earlier
 * version of these tests used hard-coded indexes and silently tested the wrong
 * column the moment the schema changed — which is exactly the failure R4 exists to
 * catch, reproduced in the test suite instead of in the field.
 */
class ShotCsvFormatTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val full = Shot(
        shotId = 42,
        shotType = R10Protos.Metrics.ShotType.NORMAL,
        receivedAtMs = 1_700_000_000_123L,
        ball = BallDisplay(101.5, 12.25, -3.5, -90.0, 2800.0, -2800.0, 0.0),
        club = ClubDisplay(89.476, 2.5, -1.5, 3.25),
        swing = SwingDisplay(1_000L, 1_900L, 2_000L, 3_000L, 3_500L, tempo = 10.0),
        rawMetrics = byteArrayOf(0x08, 0x2A, 0x00, 0x7F.toByte()),
    )

    private val minimal = Shot(
        shotId = 7,
        shotType = R10Protos.Metrics.ShotType.PRACTICE,
        receivedAtMs = 5L,
    )

    private val labelled = full.copy(clubLabel = GolfClub.IRON_SEVEN.id)

    // --- encode / decode ---

    @Test
    fun encodeDecodeRoundTripsFullShot() {
        val back = ShotCsvFormat.decode(ShotCsvFormat.encode(full))
        assertEquals(full, back) // equality includes the rawMetrics hex round-trip
        assertNotNull(back)
        assertEquals(full.ball, back?.ball)
        assertEquals(full.club, back?.club)
        assertEquals(full.swing, back?.swing)
    }

    @Test
    fun encodeDecodeRoundTripsMinimalShot() {
        val back = ShotCsvFormat.decode(ShotCsvFormat.encode(minimal))
        assertEquals(minimal, back)
        assertNull(back?.ball)
        assertNull(back?.club)
        assertNull(back?.swing)
    }

    @Test
    fun encodeDecodeRoundTripsClubLabel() {
        assertEquals(labelled, ShotCsvFormat.decode(ShotCsvFormat.encode(labelled)))
    }

    @Test
    fun anUntaggedShotStaysUntagged() {
        assertNull(ShotCsvFormat.decode(ShotCsvFormat.encode(full))?.clubLabel)
    }

    /** The label is bounded on the way in, so a hand-typed annotation cannot bloat a row. */
    @Test
    fun aTooLongClubLabelIsBoundedOnEncode() {
        val long = "W".repeat(GolfClub.ID_MAX_LEN + 5)
        val back = ShotCsvFormat.decode(ShotCsvFormat.encode(full.copy(clubLabel = long)))
        assertEquals(long.take(GolfClub.ID_MAX_LEN), back?.clubLabel)
    }

    /** `decode` is a skip-not-fail parser; validation is what turns a skip into a report. */
    @Test
    fun corruptRowsAreSkippedNotFatal() {
        assertNull(ShotCsvFormat.decode("1,2,3"))
        assertNull(ShotCsvFormat.decode("not,a,valid,row"))
        assertNull(ShotCsvFormat.decode(withColumn(ShotCsvFormat.encode(full), "shot_id", "x")))
        assertNull(ShotCsvFormat.decode(withColumn(ShotCsvFormat.encode(full), "shot_type", "BOGUS")))
        assertNull(ShotCsvFormat.decode(withColumn(ShotCsvFormat.encode(full), "received_at_ms", "later")))
    }

    // --- validation: a file we wrote ---

    /** The live export path: what the store writes must pass what the Export button runs. */
    @Test
    fun anExportFromTheStoreValidatesClean() = runTest {
        val store = ShotProtoStore(tmp.newFile())
        store.appendAll(listOf(storedShot(1), storedShot(2), storedShot(3, GolfClub.IRON_SEVEN)))

        val out = store.exportCsv(tmp.root, "test")
        val v = ShotCsvFormat.validateFile(out)

        assertTrue("export must validate: ${v.problems}", v.isClean)
        assertTrue(v.headerOk)
        assertEquals(3, v.dataRows)
        assertEquals(3, v.parsed)
        assertTrue(v.problems.isEmpty())
        assertEquals("3 rows OK", v.summary())
        assertEquals(ShotCsvFormat.HEADER, out.readLines().first { it.isNotBlank() })
    }

    @Test
    fun aMissingHeaderIsReportedNotSkipped() {
        // A lone data row with no header: the validator treats line 1 as the
        // (wrong) header, so it is flagged, not counted as a data row.
        val v = ShotCsvFormat.validateText("${ShotCsvFormat.encode(full)}\n")
        assertFalse(v.headerOk)
        assertEquals(0, v.dataRows)
        assertEquals(0, v.parsed)
        assertTrue("line 1 should be named: ${v.problems}", v.problems.first().startsWith("line 1"))
        assertTrue("header mismatch expected: ${v.problems}", "header mismatch" in v.problems.first())
    }

    /** A v1 file the user still has must validate clean, not merely load. */
    @Test
    fun aV1FileStillValidatesClean() {
        val rows = listOf(v1Row(full), v1Row(minimal), v1Row(labelled))
        val text = (listOf(ShotCsvFormat.HEADER_V1) + rows).joinToString("\n")

        val v = ShotCsvFormat.validateText(text)

        assertTrue("a v1 file must not look broken: ${v.problems}", v.isClean)
        assertEquals(3, v.dataRows)
        assertEquals(3, v.parsed)
        assertEquals("3 rows OK", v.summary())
        // ...and it still loads. A v1 row has no club_label column, so the tagged
        // shot comes back untagged — the annotation simply did not exist yet.
        assertEquals(listOf(full, minimal, full), rows.map { ShotCsvFormat.decode(it) })
    }

    // --- validation: damage ---

    /**
     * The failure mode that motivated R4: a torn row. It must be reported *with its
     * line number* and the good rows still counted, not swallowed silently.
     */
    @Test
    fun aTornRowIsReportedWithItsLineNumber() {
        val good = ShotCsvFormat.encode(full)
        // Drop the last three fields: a plausible mid-write truncation.
        val torn = good.split(',').dropLast(3).joinToString(",")
        val text = listOf(ShotCsvFormat.HEADER, good, torn, good).joinToString("\n")

        val v = ShotCsvFormat.validateText(text)

        assertTrue(v.headerOk)
        assertEquals(3, v.dataRows)
        assertEquals(2, v.parsed)
        val line3 = v.problems.single()
        assertTrue("the torn row must be reported on line 3: $line3", "line 3" in line3)
        assertTrue("a column-count problem expected: $line3", "fields" in line3)
    }

    @Test
    fun aWrongColumnCountIsReported() {
        // One field short of the current width: neither 23 nor the 21 of v1.
        val short = ShotCsvFormat.encode(full).split(',').dropLast(1).joinToString(",")
        val v = ShotCsvFormat.validateText("${ShotCsvFormat.HEADER}\n$short")
        assertEquals(1, v.dataRows)
        assertEquals(0, v.parsed)
        assertTrue("the supported widths must be named: ${v.problems}", "fields" in v.problems.single())
    }

    /**
     * The other side of the same count: a row *wider* than this schema. Read with a
     * split limit it looks current-version, with the extra field hiding inside
     * `club_label` — so it decodes and validates clean while a newer build's column
     * is silently dropped. It is a file this build cannot fully interpret, which is
     * damage, not a clean file.
     */
    @Test
    fun aRowWiderThanTheSchemaIsReported() {
        val wide = ShotCsvFormat.encode(full) + ",9I,extra"
        val v = ShotCsvFormat.validateText("${ShotCsvFormat.HEADER}\n$wide")
        assertEquals(1, v.dataRows)
        assertEquals(0, v.parsed)
        val line2 = v.problems.single()
        assertTrue("the row must be named by line: $line2", "line 2" in line2)
        assertTrue("the width must be named: $line2", "fields" in line2)
        assertNull(ShotCsvFormat.decode(wide))
    }

    /** A non-hex byte in `raw_metrics_hex` is a data-integrity error, not a guess. */
    @Test
    fun badHexInRawMetricsIsReported() {
        val bad = withColumn(ShotCsvFormat.encode(full), "raw_metrics_hex", "ZZ")
        val v = ShotCsvFormat.validateText("${ShotCsvFormat.HEADER}\n$bad")
        assertEquals(1, v.problems.size)
        assertTrue("expected raw_metrics_hex named: ${v.problems[0]}", "raw_metrics_hex" in v.problems[0])
        assertTrue("expected non-hex named: ${v.problems[0]}", "non-hex" in v.problems[0])
    }

    /** Odd-length hex is the signature of a row cut in the middle of a byte. */
    @Test
    fun oddLengthHexIsFlaggedAsTruncated() {
        val bad = withColumn(ShotCsvFormat.encode(full), "raw_metrics_hex", "0A1")
        val v = ShotCsvFormat.validateText("${ShotCsvFormat.HEADER}\n$bad")
        assertTrue("expected truncated named: ${v.problems}", "truncated" in v.problems[0])
    }

    /** A non-numeric measurement is named by column, not by position. */
    @Test
    fun aBadMeasurementIsReportedByColumnName() {
        val bad = withColumn(ShotCsvFormat.encode(full), "launch_angle_deg", "not-a-number")
        val v = ShotCsvFormat.validateText("${ShotCsvFormat.HEADER}\n$bad")
        assertTrue("expected the column named: ${v.problems}", "launch_angle_deg" in v.problems[0])
        assertTrue("expected 'not a number': ${v.problems}", "not a number" in v.problems[0])
        // ...and only that column: the integer columns either side are fine.
        assertEquals(1, v.problems.size)
    }

    @Test
    fun aBadSwingTimingIsReported() {
        val bad = withColumn(ShotCsvFormat.encode(full), "impact_us", "half-past")
        val v = ShotCsvFormat.validateText("${ShotCsvFormat.HEADER}\n$bad")
        assertTrue("expected the column named: ${v.problems}", "impact_us" in v.problems[0])
        assertTrue("expected 'not an integer': ${v.problems}", "not an integer" in v.problems[0])
    }

    @Test
    fun anUnknownShotTypeIsReported() {
        val bad = withColumn(ShotCsvFormat.encode(full), "shot_type", "BOGUS")
        val v = ShotCsvFormat.validateText("${ShotCsvFormat.HEADER}\n$bad")
        assertTrue("expected the enum named: ${v.problems}", "is not PRACTICE or NORMAL" in v.problems[0])
    }

    /**
     * An unrecognised `club_label` is accepted on purpose: it is still a label the
     * user set, and reporting it would make an older export look broken for a
     * cosmetic reason.
     */
    @Test
    fun anUnknownClubLabelIsAccepted() {
        val tagged = withColumn(ShotCsvFormat.encode(labelled), "club_label", "ZZZ")
        val v = ShotCsvFormat.validateText("${ShotCsvFormat.HEADER}\n$tagged")
        assertTrue("a cosmetic label must not fail an export: ${v.problems}", v.isClean)
        assertEquals("ZZZ", ShotCsvFormat.decode(tagged)?.clubLabel)
    }

    @Test
    fun anUnsupportedSchemaVersionIsReported() {
        val bad = withColumn(ShotCsvFormat.encode(full), "schema_version", "99")
        val v = ShotCsvFormat.validateText("${ShotCsvFormat.HEADER}\n$bad")
        assertTrue("expected the version named: ${v.problems}", "schema_version" in v.problems[0])
    }

    /** An empty file is a named error, not a silent success with 0 rows. */
    @Test
    fun anEmptyFileIsReported() {
        val v = ShotCsvFormat.validateText("")
        assertFalse(v.headerOk)
        assertFalse(v.isClean)
        assertEquals(0, v.parsed)
        assertTrue(v.problems.any { "empty" in it })
    }

    /** A file that does not exist is reported, not thrown on. */
    @Test
    fun validateFileOnAMissingFileReportsIt() {
        val v = ShotCsvFormat.validateFile(File(tmp.root, "never-written.csv"))
        assertFalse(v.isClean)
        assertTrue(v.problems.any { it.startsWith("file does not exist") })
    }

    /**
     * The summary is what a human sees in the Export message, so it must carry the
     * counts and the first few problems — enough to act on, not a wall of hex.
     */
    @Test
    fun theSummaryNamesCountsAndFirstProblems() {
        val good = ShotCsvFormat.encode(full)
        val bad = withColumn(good, "launch_angle_deg", "not-a-number")
        val v = ShotCsvFormat.validateText("${ShotCsvFormat.HEADER}\n$good\n$bad")
        val s = v.summary()
        assertTrue("summary should name the count: $s", "1/2 rows OK" in s)
        assertTrue("summary should name the problem count: $s", "1 problem(s)" in s)
        assertTrue("summary should name the bad column: $s", s.contains("launch_angle_deg"))
        assertTrue("summary should carry the problem: $s", s.contains("not a number"))
    }

    // --- hex helpers ---

    @Test
    fun hexHelpersRoundTripAllByteValues() {
        val all = ByteArray(256) { (it - 128).toByte() }
        val hex = ShotCsvFormat.toHex(all)
        assertEquals(512, hex.length)
        assertEquals("hex must be uppercase: $hex", hex.uppercase(), hex)
        assertEquals(all.toList(), ShotCsvFormat.fromHex(hex).toList())
        // ...and the encoder is the inverse of the decoder, for every byte value.
        assertEquals(hex, ShotCsvFormat.toHex(ShotCsvFormat.fromHex(hex)))
        assertEquals(ByteArray(0).toList(), ShotCsvFormat.fromHex("").toList())
        // Odd length must not decode to half a byte.
        assertEquals(ByteArray(0).toList(), ShotCsvFormat.fromHex("abc").toList())
    }

    /**
     * `encode` must be exactly reversible, so `decode(encode(shot)) == shot`. If a
     * formatting change ever lost precision or a field, this fails before an export
     * can produce a silent gap. Seeded, so a failure is reproducible.
     */
    @Test
    fun randomShotsRoundTripExactly() {
        val rnd = Random(20260925)
        repeat(500) {
            val shot = randomShot(rnd)
            val back = ShotCsvFormat.decode(ShotCsvFormat.encode(shot))
            assertEquals("round-trip failed:\n$shot\n!=\n$back", shot, back)
        }
    }

    private fun randomShot(rnd: Random): Shot {
        val tempo = rnd.nextDouble() * 1000.0
        return Shot(
            shotId = rnd.nextInt(1_000_000),
            shotType = if (rnd.nextBoolean()) {
                R10Protos.Metrics.ShotType.PRACTICE
            } else {
                R10Protos.Metrics.ShotType.NORMAL
            },
            receivedAtMs = 1_000_000_000_000L + rnd.nextLong(1_000_000),
            ball = if (rnd.nextBoolean()) {
                BallDisplay(
                    rnd.nextDouble() * 150,
                    rnd.nextDouble() * 60,
                    rnd.nextDouble() * 90 - 45,
                    rnd.nextDouble() * 360 - 180,
                    rnd.nextDouble() * 12000,
                    rnd.nextDouble() * 6000 - 3000,
                    rnd.nextDouble() * 6000 - 3000,
                )
            } else {
                null
            },
            club = if (rnd.nextBoolean()) {
                ClubDisplay(
                    rnd.nextDouble() * 120,
                    rnd.nextDouble() * 20 - 10,
                    rnd.nextDouble() * 20 - 10,
                    rnd.nextDouble() * 16 - 8,
                )
            } else {
                null
            },
            swing = if (rnd.nextBoolean()) {
                SwingDisplay(
                    rnd.nextLong(5000),
                    rnd.nextLong(5000),
                    rnd.nextLong(6000),
                    rnd.nextLong(8000),
                    rnd.nextLong(9000),
                    tempo = if (rnd.nextBoolean()) tempo else null,
                )
            } else {
                null
            },
            rawMetrics = ByteArray(rnd.nextInt(40)).also { rnd.nextBytes(it) },
            // R5/R7: the label is part of the record, so it must survive the export.
            clubLabel = if (rnd.nextBoolean()) GolfClub.entries[rnd.nextInt(GolfClub.entries.size)].id else null,
        )
    }

    // --- fixtures ---

    /**
     * A shot the store can hold, built the way the app builds one: display values
     * derived from the device's own `Metrics` bytes by [MetricConverter].
     *
     * The store recomputes the display half on load, so a hand-typed shot (the ones
     * above) would come back as different numbers — fine for format tests, wrong for
     * a round trip through the store.
     */
    private fun storedShot(id: Int, club: GolfClub? = null): Shot {
        val metrics = R10Protos.Metrics.newBuilder()
            .setShotId(id)
            .setShotType(R10Protos.Metrics.ShotType.NORMAL)
            .setBallMetrics(
                R10Protos.BallMetrics.newBuilder()
                    .setBallSpeed(41.0f)
                    .setLaunchAngle(11.5f)
                    .setLaunchDirection(-2.5f)
                    .setSpinAxis(-88.0f)
                    .setTotalSpin(7400.0f)
                    .build(),
            )
            .setClubMetrics(
                R10Protos.ClubMetrics.newBuilder()
                    .setClubHeadSpeed(42.0f)
                    .setClubAngleFace(1.5f)
                    .setClubAnglePath(-2.0f)
                    .setAttackAngle(3.5f)
                    .build(),
            )
            .build()
            .toByteArray()
        return MetricConverter.shot(R10Protos.Metrics.parseFrom(metrics), 1_700_000_000_000L + id)
            .copy(clubLabel = club?.id)
    }

    /**
     * Replace one field of a current-version row, addressed by **column name**.
     *
     * Indexing a row corrupts whichever column happens to sit there, so a schema
     * change would leave these tests green while asserting about the wrong field.
     * Naming the column fails loudly instead.
     */
    private fun withColumn(row: String, columnName: String, value: String): String {
        val index = ShotCsvFormat.COL_NAMES.indexOf(columnName)
        require(index >= 0) { "no column '$columnName' in ${ShotCsvFormat.COL_NAMES}" }
        val fields = row.split(',')
        require(index < fields.size) { "row has ${fields.size} fields, '$columnName' is at $index: $row" }
        return fields.mapIndexed { i, f -> if (i == index) value else f }.joinToString(",")
    }

    /**
     * A v1 row: the 21 original columns, with no `schema_version` and no
     * `club_label`. Derived from [ShotCsvFormat.encode] by dropping those two
     * fields, so it follows the schema instead of hard-coding 21 literals.
     */
    private fun v1Row(shot: Shot): String = ShotCsvFormat.encode(shot).split(',')
        .subList(1, ShotCsvFormat.V1_COLS + 1)
        .joinToString(",")
}

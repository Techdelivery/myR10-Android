package com.techdelivery.r10.data

import LaunchMonitor.Proto.R10Protos
import com.techdelivery.r10.protocol.shot.BallDisplay
import com.techdelivery.r10.protocol.shot.ClubDisplay
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

/**
 * M3 — shot-history persistence round-trip (DESIGN §8 / §11 CSV export).
 */
class ShotCsvStoreTest {

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

    @Test
    fun encodeDecodeRoundTripsFullShot() {
        val back = ShotCsvStore.decode(ShotCsvStore.encode(full))!!
        assertEquals(full.shotId, back.shotId)
        assertEquals(full.shotType, back.shotType)
        assertEquals(full.receivedAtMs, back.receivedAtMs)
        assertEquals(full.ball, back.ball)
        assertEquals(full.club, back.club)
        assertEquals(full.swing, back.swing)
        assertEquals(full, back) // includes rawMetrics hex round-trip
    }

    @Test
    fun encodeDecodeRoundTripsMinimalShot() {
        val back = ShotCsvStore.decode(ShotCsvStore.encode(minimal))!!
        assertEquals(minimal, back)
        assertNull(back.ball)
        assertNull(back.club)
        assertNull(back.swing)
    }

    @Test
    fun corruptRowsAreSkippedNotFatal() {
        assertNull(ShotCsvStore.decode("1,2,3"))
        assertNull(ShotCsvStore.decode("not,a,valid,row"))
        assertNull(ShotCsvStore.decode("x,NORMAL,5,,,,,,,,,,,,,,,,,,,"))
        assertNull(ShotCsvStore.decode("1,BOGUS,5,,,,,,,,,,,,,,,,,,,"))
    }

    @Test
    fun appendWritesHeaderOnceAndPreservesOrder() = runTest {
        val file = tmp.newFile()
        val store = ShotCsvStore(file)
        store.append(minimal)
        store.append(full)
        val lines = file.readLines().filter { it.isNotBlank() }
        assertEquals(ShotCsvStore.HEADER, lines.first())
        assertEquals(3, lines.size) // header + 2 rows
        val loaded = store.loadAll()
        assertEquals(listOf(7, 42), loaded.map { it.shotId })
        // appending more must not duplicate the header
        store.append(full.copy(shotId = 43))
        assertEquals(4, file.readLines().filter { it.isNotBlank() }.size)
    }

    @Test
    fun loadAllOnMissingFileIsEmpty() = runTest {
        val store = ShotCsvStore(File(tmp.root, "nope/absent.csv"))
        assertEquals(emptyList<Shot>(), store.loadAll())
    }

    @Test
    fun clearRemovesHistory() = runTest {
        val store = ShotCsvStore(tmp.newFile())
        store.appendAll(listOf(minimal, full))
        assertEquals(2, store.loadAll().size)
        store.clear()
        assertEquals(0, store.loadAll().size)
    }

    @Test
    fun exportSnapshotWritesReadableCopy() = runTest {
        val store = ShotCsvStore(tmp.newFile())
        store.appendAll(listOf(minimal, full))
        val out = store.exportSnapshot(File(tmp.root, "export"), "20260925-000000")
        assertTrue(out.exists())
        assertTrue(out.name.startsWith("r10-shots-"))
        val reparsed = ShotCsvStore(out).loadAll()
        assertEquals(listOf(7, 42), reparsed.map { it.shotId })
        assertEquals(full, reparsed[1])
    }

    @Test
    fun hexHelpersRoundTripAllByteValues() {
        val all = ByteArray(256) { (it - 128).toByte() }
        val hex = ShotCsvStore.toHex(all)
        assertEquals(512, hex.length)
        assertTrue(hex.uppercase() == hex)
        assertEquals(all.toList(), ShotCsvStore.fromHex(hex).toList())
        assertEquals(ByteArray(0).toList(), ShotCsvStore.fromHex("abc").toList()) // odd length -> empty
    }

    /**
     * W6 — cross-session dedup (DESIGN §8). A second store over the same file
     * models a new app session: a frame the device replayed must not be written
     * twice, even though the in-memory `ShotDeduper` was reset.
     */
    @Test
    fun secondStoreOnSameFileSkipsAnAlreadyPersistedFrame() = runTest {
        val file = tmp.newFile()
        assertTrue(ShotCsvStore(file).append(full))

        val nextSession = ShotCsvStore(file)
        assertFalse(
            "identical raw payload must not be persisted twice across sessions",
            nextSession.append(full),
        )
        // header + the one row that was written; the replay added nothing
        assertEquals(2, file.readLines().filter { it.isNotBlank() }.size)
        assertEquals(listOf(42), nextSession.loadAll().map { it.shotId })
    }

    /** A differing shot_id is never dropped, even on a payload collision. */
    @Test
    fun differingShotIdIsStillWritten() = runTest {
        val store = ShotCsvStore(tmp.newFile())
        assertTrue(store.append(full))
        assertTrue(store.append(full.copy(shotId = 43)))
        assertEquals(listOf(42, 43), store.loadAll().map { it.shotId })
    }

    /**
     * No raw payload means no dedup key. Such a shot must always be written —
     * keying on the empty payload would make every one of them a duplicate.
     */
    @Test
    fun shotsWithoutRawPayloadAreNeverDeduped() = runTest {
        val store = ShotCsvStore(tmp.newFile())
        assertTrue(store.append(minimal))
        assertTrue(store.append(minimal))
        assertEquals(2, store.loadAll().size)
    }

    @Test
    fun clearResetsTheDedupIndex() = runTest {
        val store = ShotCsvStore(tmp.newFile())
        assertTrue(store.append(full))
        store.clear()
        assertTrue("after a clear the same frame is fresh again", store.append(full))
    }

    /**
     * The key window is LRU, not FIFO: re-observing a key keeps it, so eviction
     * takes the genuinely-stale entry.
     */
    @Test
    fun keyWindowEvictsLeastRecentlyUsedNotFirstIn() = runTest {
        val store = ShotCsvStore(tmp.newFile(), recentKeyWindow = 2)
        val a = full.copy(shotId = 1, rawMetrics = byteArrayOf(0x01))
        val b = full.copy(shotId = 2, rawMetrics = byteArrayOf(0x02))
        val c = full.copy(shotId = 3, rawMetrics = byteArrayOf(0x03))

        assertTrue(store.append(a))
        assertTrue(store.append(b))
        assertFalse(store.append(a)) // re-observed: `a` becomes most-recently-used
        assertTrue(store.append(c)) // evicts `b`, not `a`

        assertFalse("a was touched, so it must still be known", store.append(a))
        assertTrue("b was the least recently used and should have been evicted", store.append(b))
    }

    /**
     * The durable (`rwd`) append path must leave a complete, fully-parseable file —
     * no torn line that `decode` would silently drop.
     */
    @Test
    fun durableAppendLeavesACompleteReadableFile() = runTest {
        val file = tmp.newFile()
        val store = ShotCsvStore(file)
        store.appendAll(listOf(minimal, full))
        store.append(full.copy(shotId = 99, rawMetrics = byteArrayOf(0x7F)))

        val lines = file.readLines().filter { it.isNotBlank() }
        assertEquals(4, lines.size)
        assertEquals(ShotCsvStore.HEADER, lines.first())
        lines.drop(1).forEach { assertNotNull("torn line: $it", ShotCsvStore.decode(it)) }
        assertEquals(3, store.loadAll().size)
    }

    // --- ROADMAP R4: validate the CSV instead of trusting it ---

    /** A store we wrote ourselves must validate clean. */
    @Test
    fun aStoreWeWroteValidatesClean() = runTest {
        val store = ShotCsvStore(tmp.newFile())
        store.appendAll(listOf(full, minimal, full.copy(shotId = 9, rawMetrics = byteArrayOf(0x11, 0x22))))

        val v = store.validate()
        assertTrue(v.isClean)
        assertTrue(v.headerOk)
        assertEquals(3, v.dataRows)
        assertEquals(3, v.parsed)
        assertTrue(v.problems.isEmpty())
        assertEquals("3 rows OK", v.summary())
    }

    /** A missing header is the first thing a corrupt file gets hit with. */
    @Test
    fun aMissingHeaderIsReportedNotSkipped() {
        // A lone data row with no header: the validator treats line 1 as the
        // (wrong) header, so it is flagged, not counted as a data row.
        val text = "${ShotCsvStore.encode(full)}\n"
        val v = ShotCsvStore.validateText(text)
        assertFalse(v.headerOk)
        assertEquals(0, v.dataRows)
        assertEquals(0, v.parsed)
        assertTrue("line 1: header mismatch", v.problems.first().startsWith("line 1"))
    }

    /**
     * The failure mode that motivated R4: a torn row. It must be reported *with its
     * line number* and the good rows still counted, not swallowed silently.
     */
    @Test
    fun aTornRowIsReportedWithItsLineNumber() {
        val good = ShotCsvStore.encode(full)
        // Drop the last three fields: a plausible mid-write truncation.
        val torn = good.split(",").dropLast(3).joinToString(",")
        val text = listOf(ShotCsvStore.HEADER, good, torn, good).joinToString("\n")

        val v = ShotCsvStore.validateText(text)
        assertTrue(v.headerOk)
        assertEquals(3, v.dataRows)
        assertEquals(2, v.parsed)
        val line3 = v.problems.firstOrNull()
        requireNotNull(line3) { "expected a problem for line 3, got ${v.problems}" }
        assertTrue("line 3 should be reported: $line3", "line 3" in line3)
        assertTrue("column-count problem expected: $line3", "fields" in line3)
    }

    /** A non-hex byte in raw_metrics_hex is a data-integrity error, not a guess. */
    @Test
    fun badHexInRawMetricsIsReported() {
        val good = ShotCsvStore.encode(full)
        val f = good.split(",", limit = 21)
        val bad = f.mapIndexed { i, v -> if (i == 20) "ZZ" else v }.joinToString(",")
        val v = ShotCsvStore.validateText("${ShotCsvStore.HEADER}\n$bad")
        assertEquals(1, v.problems.size)
        assertTrue("expected raw_metrics_hex named: ${v.problems[0]}", "raw_metrics_hex" in v.problems[0])
        assertTrue("expected non-hex named: ${v.problems[0]}", "non-hex" in v.problems[0])
    }

    /** Odd-length hex is the signature of a row cut in the middle of a byte. */
    @Test
    fun oddLengthHexIsFlaggedAsTruncated() {
        val good = ShotCsvStore.encode(full)
        val f = good.split(",", limit = 21)
        val bad = f.mapIndexed { i, v -> if (i == 20) "0A1" else v }.joinToString(",")
        val v = ShotCsvStore.validateText("${ShotCsvStore.HEADER}\n$bad")
        assertTrue("expected truncated named: ${v.problems[0]}", "truncated" in v.problems[0])
    }

    /** An empty file is a named error, not a silent success with 0 rows. */
    @Test
    fun anEmptyFileIsReported() {
        val v = ShotCsvStore.validateText("")
        assertFalse(v.headerOk)
        assertEquals(0, v.parsed)
        assertTrue(v.problems.any { "empty" in it })
    }

    /** The Export button validates the file it just wrote. */
    @Test
    fun validateFileChecksTheWrittenCopy() = runTest {
        val store = ShotCsvStore(tmp.newFile())
        store.appendAll(listOf(full, minimal))
        val out = store.exportSnapshot(tmp.root, "test")
        val v = store.validateFile(out)
        assertTrue(v.isClean)
        assertEquals(2, v.parsed)
    }

    /** A file that does not exist is reported, not thrown on. */
    @Test
    fun validateFileOnAMissingFileReportsIt() {
        val v = ShotCsvStore(tmp.newFile()).validateFile(File(tmp.root, "never-written.csv"))
        assertFalse(v.isClean)
        assertTrue(v.problems.any { it.startsWith("file does not exist") })
    }

    /**
     * The summary is what a human sees in the Export message, so it must carry the
     * counts and the first few problems — enough to act on, not a wall of hex.
     */
    @Test
    fun theSummaryNamesCountsAndFirstProblems() {
        val good = ShotCsvStore.encode(full)
        val bad = good.split(",", limit = 21).mapIndexed { i, v -> if (i == 4) "not-a-number" else v }.joinToString(",")
        val v = ShotCsvStore.validateText("${ShotCsvStore.HEADER}\n$good\n$bad")
        val s = v.summary()
        assertTrue("summary should name the count: $s", "1/2 rows OK" in s)
        assertTrue("summary should name the bad column: $s", s.contains("launch_angle_deg"))
        assertTrue("summary should carry the first problem: $s", s.contains("not a number"))
    }

    /**
     * encode must be exactly reversible so that `decode(encode(shot)) == shot`. If a
     * formatting change ever lost precision or a field, this would fail before the
     * CSV export ever produced a silent gap.
     */
    @Test
    fun randomShotsRoundTripExactly() {
        val rnd = java.util.Random(20260925)
        repeat(200) {
            val hasBall = rnd.nextBoolean()
            val hasClub = rnd.nextBoolean()
            val hasSwing = rnd.nextBoolean()
            val n = rnd.nextDouble() * 1000.0
            val shot = Shot(
                shotId = rnd.nextInt(1_000_000),
                shotType = if (rnd.nextBoolean()) {
                    R10Protos.Metrics.ShotType.PRACTICE
                } else {
                    R10Protos.Metrics.ShotType.NORMAL
                },
                receivedAtMs = 1_000_000_000_000L + rnd.nextLong(1_000_000),
                ball = if (hasBall) {
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
                club = if (hasClub) {
                    ClubDisplay(
                        rnd.nextDouble() * 120,
                        rnd.nextDouble() * 20 - 10,
                        rnd.nextDouble() * 20 - 10,
                        rnd.nextDouble() * 16 - 8,
                    )
                } else {
                    null
                },
                swing = if (hasSwing) {
                    SwingDisplay(
                        rnd.nextLong(5000),
                        rnd.nextLong(5000),
                        rnd.nextLong(6000),
                        rnd.nextLong(8000),
                        rnd.nextLong(9000),
                        tempo = if (rnd.nextBoolean()) n else null,
                    )
                } else {
                    null
                },
                rawMetrics = ByteArray(rnd.nextInt(40)).also { rnd.nextBytes(it) },
            )
            val back = ShotCsvStore.decode(ShotCsvStore.encode(shot))
            assertEquals("round-trip failed:\n$shot\n!=\n$back", shot, back)
        }
    }
}

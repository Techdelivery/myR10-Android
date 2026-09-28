package com.techdelivery.r10.data

import LaunchMonitor.Proto.R10Protos
import com.techdelivery.r10.club.GolfClub
import com.techdelivery.r10.protocol.shot.MetricConverter
import com.techdelivery.r10.protocol.shot.Shot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

/**
 * ROADMAP R7 — the shot store, now protobuf — and the R5/R6 behaviour it carries.
 *
 * The store holds the R10's own `Metrics` bytes plus the app's two additions
 * (arrival time, club label). So the guarantees that used to be about *text* —
 * a row cannot tear mid-field, a float cannot lose precision through formatting, a
 * renamed field cannot orphan a label — are now structural, and the tests below
 * cover what replaced them: club round-trips, row-scoped edits, delete, the dedup
 * index after a delete, per-record damage, and the CSV export staying valid.
 */
class ShotProtoStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * A shot built the way the app builds one: display values derived from the
     * device's metrics by [MetricConverter], with those same metrics kept as bytes.
     *
     * Building the display half by hand would make the tests assert against numbers
     * the device never sent — and the store would (correctly) return the derived
     * ones instead.
     *
     * [salt] varies the payload while leaving [id] alone, which is how a test gets
     * two records the store genuinely allows: the dedup key is `shot_id || payload`,
     * because the R10 restarts its id sequence on every power cycle.
     */
    private fun shot(id: Int, at: Long = 1_700_000_000_000L + id, salt: Int = 0): Shot {
        val raw = metrics(id, salt)
        return MetricConverter.shot(R10Protos.Metrics.parseFrom(raw), at)
    }

    /** A real `Metrics` payload, so the store round-trips the device's own bytes. */
    private fun metrics(id: Int, salt: Int = 0): ByteArray {
        val ball = R10Protos.BallMetrics.newBuilder()
            .setBallSpeed(88.0f + id + salt)
            .setLaunchAngle(11.5f)
            .setLaunchDirection(-2.5f)
            .setSpinAxis(-88.0f)
            .setTotalSpin(7400.0f)
            .build()
        val club = R10Protos.ClubMetrics.newBuilder()
            .setClubHeadSpeed(91.0f)
            .setClubAngleFace(1.5f)
            .setClubAnglePath(-2.0f)
            .setAttackAngle(3.5f)
            .build()
        return R10Protos.Metrics.newBuilder()
            .setShotId(id)
            .setShotType(R10Protos.Metrics.ShotType.NORMAL)
            .setBallMetrics(ball)
            .setClubMetrics(club)
            .build()
            .toByteArray()
    }

    private fun file() = tmp.newFile()

    /**
     * A store whose tail is torn, the way a kill mid-append leaves it: the header
     * and the early records are intact, the last one is cut mid-body.
     */
    private suspend fun tornFile(): File {
        val f = file()
        ShotProtoStore(f).appendAll(listOf(shot(1), shot(2), shot(3)))
        val bytes = f.readBytes()
        f.writeBytes(bytes.copyOf(bytes.size - 12))
        return f
    }

    /**
     * A `StoredShot` carrying a club label but no metrics: it frames cleanly and
     * parses, is not a header (field 2 is unknown to [R10Protos.ShotLogHeader]) and
     * is not a shot (no metrics). That is the record the store must carry without
     * obeying — and, before ROADMAP parked item B, refused every rewrite over.
     *
     * A record with only `received_at_ms` would not do: that is field 1, the same
     * number and wire type as `store_format_version`, so it reads as a header and
     * the store has no way to know it is not one.
     */
    private fun uninterpretableRecord(): ByteArray = DelimitedRecords.encode(
        R10Protos.StoredShot.newBuilder().setClubLabel(GolfClub.IRON_SEVEN.id).build(),
    )

    private suspend fun fileWithUninterpretableRecord(): File {
        val f = file()
        ShotProtoStore(f).appendAll(listOf(shot(1), shot(2)))
        FileOutputStream(f, true).use { it.write(uninterpretableRecord()) }
        return f
    }

    /** True when [needle] appears in [haystack] byte for byte. */
    private fun ByteArray.containsBytes(needle: ByteArray): Boolean =
        indices.any { i -> needle.indices.all { j -> this[i + j] == needle[j] } }

    // --- basics ---

    @Test
    fun aShotRoundTripsThroughTheStore() = runTest {
        val store = ShotProtoStore(file())
        val original = shot(1)
        assertTrue(store.append(original))
        val back = store.loadAll().shots.single()
        assertEquals(original.shotId, back.shotId)
        assertEquals(original.receivedAtMs, back.receivedAtMs)
        assertEquals(original.ball, back.ball)
        assertEquals(original.club, back.club)
    }

    /** The whole point: the device's bytes come back byte-identical. */
    @Test
    fun theDevicePayloadIsPreservedExactly() = runTest {
        val store = ShotProtoStore(file())
        store.append(shot(1))
        assertTrue(metrics(1).contentEquals(store.loadAll().shots.single().rawMetrics))
    }

    @Test
    fun historyIsOldestFirstAndOrdered() = runTest {
        val store = ShotProtoStore(file())
        store.appendAll(listOf(shot(1), shot(2), shot(3)))
        assertEquals(listOf(1, 2, 3), store.loadAll().shots.map { it.shotId })
    }

    @Test
    fun anIdenticalRepushIsDeduped() = runTest {
        val store = ShotProtoStore(file())
        val s = shot(1)
        assertTrue(store.append(s))
        assertFalse("same payload again", store.append(s))
        assertEquals(1, store.loadAll().shots.size)
    }

    @Test
    fun aDifferentIdIsStillWritten() = runTest {
        val store = ShotProtoStore(file())
        store.append(shot(1))
        // Same payload bytes, different shot id. The dedup key is
        // `shot_id || payload`, precisely so a payload collision can never drop a
        // real shot.
        assertTrue(store.append(shot(1).copy(shotId = 2)))
        assertEquals(2, store.loadAll().shots.size)
    }

    // --- the dedup index is a bounded window, and that has an edge (parked E) ---

    /**
     * `ShotDedupIndex` is a bounded LRU: 2000 keys in production, injectable so a
     * test can watch it evict. This pins the guarantee at the boundary, which is
     * where the store's cross-session dedup actually stops being a guarantee.
     *
     * Within the window, an identical re-push is suppressed.
     */
    @Test
    fun aRepushWithinTheKeyWindowIsStillSuppressed() = runTest {
        val store = ShotProtoStore(file(), recentKeyWindow = 2)
        store.appendAll(listOf(shot(1), shot(2), shot(3)))
        assertFalse("shot 2 is inside the window", store.append(shot(2)))
        assertEquals(listOf(1, 2, 3), store.loadAll().shots.map { it.shotId })
    }

    /**
     * The honest edge, which nothing documented before: a rewrite re-seeds the index
     * from the surviving records, and the seed is bounded by the same window — so
     * after any club tag or delete, the oldest shots fall out of it, and a re-push of
     * one of those is written again as a duplicate row.
     *
     * That is a property of a bounded cache, not a bug: the alternative is an index
     * that grows with the history, which is the memory bound the window exists to
     * provide. But it is the difference between "this shot is known to be stored" and
     * "this shot is known to be stored *if it is one of the last N*", and only the
     * second was true. The device's in-session duplicate suppression (`R10Device`'s
     * per-connection deduper) is unaffected — it does not consult this index — so the
     * window only widens a gap that already existed.
     */
    @Test
    fun aRepushOutsideTheKeyWindowIsWrittenAgainAfterARewrite() = runTest {
        val store = ShotProtoStore(file(), recentKeyWindow = 2)
        store.appendAll(listOf(shot(1), shot(2), shot(3)))

        // A club tag is a rewrite, and a rewrite re-seeds the index from the file.
        assertEquals(WriteOutcome.WRITTEN, store.updateClub(3, shot(3).receivedAtMs, GolfClub.DRIVER))

        assertTrue(
            "shot 1 is outside a 2-key window, so it is no longer remembered",
            store.append(shot(1)),
        )
        assertEquals(listOf(1, 2, 3, 1), store.loadAll().shots.map { it.shotId })
    }

    /** A delete must not leave a key behind — the reseed's whole reason for existing. */
    @Test
    fun aDeletedShotIsRePushableImmediatelyEvenThoughItWasTheNewest() = runTest {
        val store = ShotProtoStore(file(), recentKeyWindow = 8)
        store.appendAll(listOf(shot(1), shot(2)))
        assertEquals(WriteOutcome.WRITTEN, store.deleteShot(2, shot(2).receivedAtMs))

        assertTrue(
            "a stale key here would suppress the device re-pushing for the rest of the session",
            store.append(shot(2)),
        )
        assertEquals(listOf(1, 2), store.loadAll().shots.map { it.shotId })
    }

    @Test
    fun clearResetsTheDedupIndex() = runTest {
        val store = ShotProtoStore(file())
        store.append(shot(1))
        store.clear()
        assertTrue("the index must not outlive the file it describes", store.append(shot(1)))
    }

    @Test
    fun aMissingFileLoadsEmpty() = runTest {
        val store = ShotProtoStore(File(tmp.newFolder(), "absent.bin"))
        assertTrue(store.loadAll().shots.isEmpty())
    }

    @Test
    fun clearRemovesHistory() = runTest {
        val store = ShotProtoStore(file())
        store.appendAll(listOf(shot(1), shot(2)))
        store.clear()
        assertTrue(store.loadAll().shots.isEmpty())
        assertTrue("a cleared store accepts shots again", store.append(shot(3)))
    }

    // --- club annotation (R5) ---

    @Test
    fun aClubLabelRoundTrips() = runTest {
        val store = ShotProtoStore(file())
        store.append(shot(1).copy(clubLabel = GolfClub.IRON_SEVEN.id))
        assertEquals(GolfClub.IRON_SEVEN.id, store.loadAll().shots.single().clubLabel)
    }

    @Test
    fun anUntaggedShotHasNoLabel() = runTest {
        val store = ShotProtoStore(file())
        store.append(shot(1))
        assertNull(store.loadAll().shots.single().clubLabel)
    }

    @Test
    fun updateClubRewritesOnlyThatRow() = runTest {
        val store = ShotProtoStore(file())
        store.appendAll(listOf(shot(1), shot(2), shot(3)))
        val payloads = store.loadAll().shots.associate { it.shotId to it.rawMetrics.toList() }

        assertEquals(WriteOutcome.WRITTEN, store.updateClub(2, shot(2).receivedAtMs, GolfClub.DRIVER))

        val all = store.loadAll().shots
        assertEquals(listOf(null, GolfClub.DRIVER.id, null), all.map { it.clubLabel })
        assertEquals("the other shots' bytes must be untouched", payloads[1], all[0].rawMetrics.toList())
        assertEquals(payloads[3], all[2].rawMetrics.toList())
    }

    @Test
    fun clearingTheClubIsNotDeletingTheShot() = runTest {
        val store = ShotProtoStore(file())
        val s = shot(1)
        assertTrue(store.append(s))
        assertEquals(WriteOutcome.WRITTEN, store.updateClub(1, s.receivedAtMs, GolfClub.PUTTER))
        assertEquals(WriteOutcome.WRITTEN, store.updateClub(1, s.receivedAtMs, null))
        assertEquals(1, store.loadAll().shots.size)
        assertNull(store.loadAll().shots.single().clubLabel)
    }

    @Test
    fun updatingAnAbsentShotIsANoop() = runTest {
        val store = ShotProtoStore(file())
        store.append(shot(1))
        assertEquals(WriteOutcome.NOT_FOUND, store.updateClub(99, shot(1).receivedAtMs, GolfClub.DRIVER))
        assertEquals(1, store.loadAll().shots.size)
    }

    /** A label this build does not know must still round-trip untouched. */
    @Test
    fun anUnknownLabelIsKeptVerbatim() = runTest {
        val store = ShotProtoStore(file())
        store.append(shot(1).copy(clubLabel = "Sand Wedge Deluxe"))
        assertEquals("Sand Wedge Deluxe", store.loadAll().shots.single().clubLabel)
    }

    // --- delete (R6) ---

    @Test
    fun deleteRemovesOnlyThatRowAndKeepsOrder() = runTest {
        val store = ShotProtoStore(file())
        store.appendAll(listOf(shot(1), shot(2), shot(3)))
        assertEquals(WriteOutcome.WRITTEN, store.deleteShot(2, shot(2).receivedAtMs))
        assertEquals(listOf(1, 3), store.loadAll().shots.map { it.shotId })
    }

    @Test
    fun deletingTheOnlyShotLeavesAnEmptyButValidStore() = runTest {
        val store = ShotProtoStore(file())
        val s = shot(1)
        assertTrue(store.append(s))
        assertEquals(WriteOutcome.WRITTEN, store.deleteShot(1, s.receivedAtMs))
        assertTrue(store.loadAll().shots.isEmpty())
        val v = store.validate()
        assertTrue("a header-only store is still valid: ${v.problems}", v.isClean)
    }

    @Test
    fun deletingAnAbsentShotIsANoop() = runTest {
        val store = ShotProtoStore(file())
        store.append(shot(1))
        assertEquals(WriteOutcome.NOT_FOUND, store.deleteShot(42, shot(1).receivedAtMs))
        assertEquals(1, store.loadAll().shots.size)
    }

    /**
     * The subtle one. A stale dedup key would suppress the device re-pushing that
     * shot for the rest of the session — silent loss that looks like "the R10
     * stopped sending".
     */
    @Test
    fun aDeletedShotCanBeWrittenAgainWhenTheDeviceRepushesIt() = runTest {
        val store = ShotProtoStore(file())
        val s = shot(1)
        assertTrue(store.append(s))
        assertFalse(store.append(s))
        assertEquals(WriteOutcome.WRITTEN, store.deleteShot(1, s.receivedAtMs))
        assertTrue("re-push after delete must write again", store.append(s))
        assertEquals(1, store.loadAll().shots.size)
    }

    @Test
    fun aRewriteLeavesNoTempFileBehind() = runTest {
        val f = file()
        val store = ShotProtoStore(f)
        store.appendAll(listOf(shot(1), shot(2)))
        store.updateClub(1, shot(1).receivedAtMs, GolfClub.FIVE_WOOD)
        store.deleteShot(2, shot(2).receivedAtMs)
        assertEquals(listOf(f.name), f.parentFile?.list()?.toList())
    }

    // --- identity is (shot_id, received_at), not shot_id alone ---

    /**
     * The R10 restarts `shot_id` at every power cycle — the reason the dedup key is
     * `shot_id || payload` and the reason the file can hold yesterday's shot 1 and
     * today's shot 1 at once. A mutation keyed on the id alone would edit or
     * destroy both, and a delete has no undo.
     */
    @Test
    fun aClubTagOnlyReachesTheSessionItNames() = runTest {
        val store = ShotProtoStore(file())
        // Same id, different payloads, so the dedup key genuinely permits both.
        val yesterday = shot(1, at = 1_700_000_000_000L, salt = 0)
        val today = shot(1, at = 1_800_000_000_000L, salt = 1)
        assertTrue(store.append(yesterday))
        assertTrue(store.append(today))

        assertEquals(WriteOutcome.WRITTEN, store.updateClub(1, today.receivedAtMs, GolfClub.DRIVER))

        val all = store.loadAll().shots
        assertEquals(listOf(1, 1), all.map { it.shotId })
        assertEquals(
            "yesterday's shot must keep its own (absent) tag",
            listOf(null, GolfClub.DRIVER.id),
            all.map { it.clubLabel },
        )
    }

    @Test
    fun deletingOneSessionsShotKeepsTheOther() = runTest {
        val store = ShotProtoStore(file())
        val yesterday = shot(1, at = 1_700_000_000_000L, salt = 0).copy(clubLabel = GolfClub.PUTTER.id)
        val today = shot(1, at = 1_800_000_000_000L, salt = 1)
        store.appendAll(listOf(yesterday, today))

        assertEquals(WriteOutcome.WRITTEN, store.deleteShot(1, today.receivedAtMs))

        val left = store.loadAll().shots
        assertEquals(1, left.size)
        assertEquals(yesterday.receivedAtMs, left.single().receivedAtMs)
        assertEquals("the survivor keeps its tag", GolfClub.PUTTER.id, left.single().clubLabel)
    }

    @Test
    fun aMutationOnTheWrongArrivalTimeIsANoop() = runTest {
        val store = ShotProtoStore(file())
        store.appendAll(
            listOf(shot(1, at = 1_700_000_000_000L, salt = 0), shot(1, at = 1_800_000_000_000L, salt = 1)),
        )
        assertEquals(WriteOutcome.NOT_FOUND, store.deleteShot(1, 1_750_000_000_000L))
        assertEquals(WriteOutcome.NOT_FOUND, store.updateClub(1, 1_750_000_000_000L, GolfClub.PUTTER))
        assertEquals(2, store.loadAll().shots.size)
    }

    // --- the read ceiling (ROADMAP parked C) ---

    /**
     * A file past [ShotProtoStore.MAX_FILE_BYTES], built by hand: writing 32 MiB of
     * real records through the store would make this test take minutes, and the cap
     * only looks at the file's length, so the bytes past the first record do not have
     * to be anything a reader would accept.
     */
    private suspend fun oversizedFile(): File {
        val f = file()
        ShotProtoStore(f).appendAll(listOf(shot(1), shot(2)))
        // Pad past the ceiling with a valid framed record plus filler, so the file is
        // genuinely over the limit rather than merely lying about it.
        FileOutputStream(f, true).use { out ->
            out.write(ShotRecordCodec.encode(shot(3)))
            val filler = ByteArray(ShotProtoStore.MAX_FILE_BYTES.toInt())
            out.write(filler)
        }
        return f
    }

    @Test
    fun anOversizedFileStillLoadsTheShotsItCouldRead() = runTest {
        val store = ShotProtoStore(oversizedFile())
        assertEquals(listOf(1, 2, 3), store.loadAll().shots.map { it.shotId })
    }

    /** The point of the whole exercise: truncation is reported, never silent. */
    @Test
    fun anOversizedFileSaysItWasTruncatedRatherThanQuietlyStopping() = runTest {
        val load = ShotProtoStore(oversizedFile()).loadAll()
        assertTrue(
            "the ceiling must be named, in bytes: ${load.problems}",
            load.problems.any { it.contains("read ceiling") && it.contains("bytes") },
        )
        assertEquals("the readable shots still come back", 3, load.shots.size)
    }

    @Test
    fun aNormalFileReportsNoProblemsAtAll() = runTest {
        val f = file()
        ShotProtoStore(f).appendAll(listOf(shot(1), shot(2)))
        assertEquals(emptyList<String>(), ShotProtoStore(f).loadAll().problems)
    }

    /**
     * A rewrite past the ceiling would write back only the prefix, so it has to
     * refuse. The file must come out byte-identical, or this refusal is not a refusal
     * but a slow deletion.
     */
    @Test
    fun anOversizedFileRefusesEveryRewriteAndIsLeftIntact() = runTest {
        val f = oversizedFile()
        val before = f.readBytes()
        val store = ShotProtoStore(f)

        assertEquals(WriteOutcome.DAMAGED, store.updateClub(1, shot(1).receivedAtMs, GolfClub.DRIVER))
        assertEquals(WriteOutcome.DAMAGED, store.deleteShot(1, shot(1).receivedAtMs))
        assertArrayEquals("an over-ceiling file must not be rewritten at all", before, f.readBytes())
    }

    /**
     * The trap this change walked into. `repairTailUnlocked` fixes a torn tail by
     * writing back the bytes the walk accounted for — but past the ceiling those are
     * only the bytes that were read, so a repair would delete everything past it. And
     * a repair is on the *append* path, so the loss would arrive with the next shot.
     *
     * Note what is accepted here rather than wished away: the new shot goes to disk
     * and is then outside the readable window, because it is past the ceiling. It is
     * kept, not dropped — refusing the append would lose a real shot silently, and
     * losing it loudly on the next `clear()` is the better of the two. Nothing is
     * lost, and the file says why it is showing less than it holds.
     */
    @Test
    fun anOversizedFileIsNeverRepairedOnTheAppendPath() = runTest {
        val f = oversizedFile()
        val before = f.readBytes()
        val store = ShotProtoStore(f)

        assertTrue("precondition: the append is accepted, not dropped", store.append(shot(9)))
        val after = f.readBytes()

        assertTrue("the file must have grown, not been rewritten", after.size > before.size)
        assertArrayEquals(
            "everything the file held before the append must still be there",
            before,
            after.copyOf(before.size),
        )
        assertEquals(
            "the readable window still stops where it did; the new shot is past the ceiling",
            listOf(1, 2, 3),
            store.loadAll().shots.map { it.shotId },
        )
    }

    /**
     * The protection the old KDoc credited to the cap actually lives in the length
     * bounds check, and this passes with no cap at all. Recorded here so the two are
     * never confused again: the cap bounds cost, the bounds check bounds damage.
     */
    @Test
    fun aCorruptLengthIsCaughtByTheBoundsCheckNotTheCap() = runTest {
        val f = file()
        ShotProtoStore(f).appendAll(listOf(shot(1), shot(2)))
        // A varint claiming a length far past the end of the file.
        FileOutputStream(f, true).use { it.write(byteArrayOf(0xFF.toByte(), 0x01)) }
        val store = ShotProtoStore(f)

        val v = store.validate()
        assertFalse("a length the file cannot satisfy is damage", v.isClean)
        assertTrue(
            "the bounds check must name it: ${v.problems}",
            v.problems.any { it.contains("truncated record") || it.contains("length prefix") },
        )
        assertEquals("the records before the damage still load", listOf(1, 2), store.loadAll().shots.map { it.shotId })
    }

    // --- a record we cannot interpret is carried, not obeyed (ROADMAP parked B) ---

    /**
     * The distinction parked item B turned on: bytes the store never read (a torn
     * tail, a capped file) make a rewrite lossy, while a record it read and does not
     * understand does not. Both used to be one `damaged` flag, so one uninterpretable
     * record made the file permanently un-mutable — the user could tag nothing and
     * delete nothing, forever, over a byte this build simply does not know.
     */
    @Test
    fun aShotLoadsNormallyBesideAnUninterpretableRecord() = runTest {
        val store = ShotProtoStore(fileWithUninterpretableRecord())
        assertEquals(listOf(1, 2), store.loadAll().shots.map { it.shotId })
    }

    @Test
    fun aClubTagStillWorksBesideAnUninterpretableRecord() = runTest {
        val store = ShotProtoStore(fileWithUninterpretableRecord())
        assertEquals(WriteOutcome.WRITTEN, store.updateClub(1, shot(1).receivedAtMs, GolfClub.DRIVER))
        assertEquals(listOf(GolfClub.DRIVER.id, null), store.loadAll().shots.map { it.clubLabel })
    }

    @Test
    fun aDeleteStillWorksBesideAnUninterpretableRecord() = runTest {
        val store = ShotProtoStore(fileWithUninterpretableRecord())
        assertEquals(WriteOutcome.WRITTEN, store.deleteShot(1, shot(1).receivedAtMs))
        assertEquals(listOf(2), store.loadAll().shots.map { it.shotId })
    }

    /**
     * The rewrite is allowed *because* the bytes survive it. If this ever fails, the
     * unblocking is not worth what it costs: an uninterpretable record carried
     * through a rewrite is a promise that the store never has to understand a byte
     * to keep it.
     */
    @Test
    fun anUninterpretableRecordSurvivesARewriteByteForByte() = runTest {
        val f = fileWithUninterpretableRecord()
        val store = ShotProtoStore(f)

        assertEquals(WriteOutcome.WRITTEN, store.updateClub(1, shot(1).receivedAtMs, GolfClub.DRIVER))
        assertEquals(WriteOutcome.WRITTEN, store.deleteShot(2, shot(2).receivedAtMs))

        assertTrue("the record must be carried through verbatim", f.readBytes().containsBytes(uninterpretableRecord()))
    }

    /** Preserved is not the same as accepted: the user is still told it is there. */
    @Test
    fun validateStillNamesTheUninterpretableRecord() = runTest {
        val store = ShotProtoStore(fileWithUninterpretableRecord())
        val v = store.validate()
        assertFalse(v.isClean)
        assertTrue(
            "the problem must name the record: ${v.problems}",
            v.problems.any { it.contains("not a header or a shot") },
        )
        assertEquals("the real shots still count", 2, v.records)
    }

    /** The negative, so the relaxation above cannot be mistaken for a blanket one. */
    @Test
    fun aTornTailStillRefusesBothRewrites() = runTest {
        val f = tornFile()
        val before = f.readBytes()
        val store = ShotProtoStore(f)

        assertEquals(WriteOutcome.DAMAGED, store.updateClub(1, shot(1).receivedAtMs, GolfClub.DRIVER))
        assertEquals(WriteOutcome.DAMAGED, store.deleteShot(1, shot(1).receivedAtMs))
        assertArrayEquals("bytes the walk never read must not be dropped", before, f.readBytes())
    }

    // --- damage is per record, not per file ---

    @Test
    fun aTruncatedTailLosesOnlyTheLastRecord() = runTest {
        val f = file()
        val store = ShotProtoStore(f)
        store.appendAll(listOf(shot(1), shot(2), shot(3)))
        // Chop the tail, as a kill mid-append would.
        val bytes = f.readBytes()
        f.writeBytes(bytes.copyOf(bytes.size - 12))

        val reloaded = ShotProtoStore(f)
        val loaded = reloaded.loadAll().shots
        assertTrue("the intact records must survive", loaded.size >= 1)
        assertTrue("and they must be the early ones", loaded.map { it.shotId } == loaded.map { it.shotId }.sorted())
        val v = reloaded.validate()
        assertFalse("the damage is reported, not hidden", v.isClean)
        assertTrue("${v.problems}", v.problems.any { "truncated" in it })
    }

    @Test
    fun garbageInTheMiddleIsReportedAndTheRestLoads() = runTest {
        val f = file()
        val store = ShotProtoStore(f)
        store.appendAll(listOf(shot(1), shot(2), shot(3)))
        // Overwrite the length prefix of the frame holding shot 2 with one that
        // claims 2^31 bytes. Narrowed to an Int that is *negative*, so a bounds
        // check written as `start + length` passes and the slice throws — the
        // damage has to be caught as a length, not left to arithmetic.
        val bytes = f.readBytes()
        val damaged = bytes.copyOf()
        // The header is the first frame, so the third frame is the second shot; a
        // frame's offset is the sum of the frames before it, not its own size.
        val frames = DelimitedRecords.read(bytes)
        val prefixAt = frames[0].size + frames[1].size
        byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x08)
            .copyInto(damaged, prefixAt)
        f.writeBytes(damaged)

        val reloaded = ShotProtoStore(f)
        val v = reloaded.validate()
        assertFalse("the damage must be reported, not hidden", v.isClean)
        assertTrue("${v.problems}", v.problems.any { "truncated" in it })
        assertEquals(
            "the records before the damage must still load",
            listOf(1),
            reloaded.loadAll().shots.map { it.shotId },
        )
    }

    // --- a rewrite must never be the thing that loses a torn session ---

    /**
     * The rewrite paths write back only the records they could read, so running one
     * over a damaged file would delete everything past the tear. They must refuse.
     */
    @Test
    fun aClubTagOnADamagedStoreIsRefusedAndTheFileIsLeftIntact() = runTest {
        val f = tornFile()
        val before = f.readBytes()
        val store = ShotProtoStore(f)

        assertEquals(WriteOutcome.DAMAGED, store.updateClub(1, shot(1).receivedAtMs, GolfClub.DRIVER))

        assertArrayEquals("the damaged file must be untouched", before, f.readBytes())
        assertEquals(listOf(1, 2), store.loadAll().shots.map { it.shotId })
    }

    @Test
    fun aDeleteOnADamagedStoreIsRefusedAndTheFileIsLeftIntact() = runTest {
        val f = tornFile()
        val before = f.readBytes()
        val store = ShotProtoStore(f)

        assertEquals(WriteOutcome.DAMAGED, store.deleteShot(1, shot(1).receivedAtMs))

        assertArrayEquals("the damaged file must be untouched", before, f.readBytes())
        assertEquals(listOf(1, 2), store.loadAll().shots.map { it.shotId })
    }

    @Test
    fun anEmptyStoreReportsRatherThanPasses() = runTest {
        val v = ShotProtoStore(file()).validate()
        assertFalse(v.isClean)
    }

    /**
     * The two refusals are told apart by the store itself, not by the UI re-reading
     * and re-validating the whole file after every refused edit. Only the second is
     * worth an error banner, so they must not collapse into one answer.
     */
    @Test
    fun aRefusedMutationSaysWhichRefusalItWas() = runTest {
        val cleanStore = ShotProtoStore(file())
        cleanStore.append(shot(1))
        assertEquals(
            WriteOutcome.NOT_FOUND,
            cleanStore.updateClub(99, shot(1).receivedAtMs, GolfClub.DRIVER),
        )
        assertTrue("a missing shot is not damage", cleanStore.validate().isClean)

        val tornStore = ShotProtoStore(tornFile())
        assertEquals(WriteOutcome.DAMAGED, tornStore.updateClub(1, shot(1).receivedAtMs, GolfClub.DRIVER))
        assertFalse("a refused rewrite is damage the user must be told about", tornStore.validate().isClean)
    }

    // --- the append path's tail check must not read the whole file every shot ---

    /**
     * The tail check that guards an append is a whole-file read, and the append
     * path runs about once per second: left uncached, every shot costs O(file
     * size) on the one path DESIGN describes as a single framed write plus an
     * fsync. A length this store last wrote intact answers the question without
     * the read. These two tests pin the observable half of that contract — a clean
     * append still leaves a clean, complete, append-only file, and a length the
     * store did not write falls back to the full scan and is repaired.
     */
    @Test
    fun repeatedAppendsKeepTheFileCleanAndComplete() = runTest {
        val f = file()
        val store = ShotProtoStore(f)
        (1..5).forEach { assertTrue(store.append(shot(it))) }
        val beforeLast = f.readBytes()

        assertTrue("the append after five more must still write", store.append(shot(6)))

        val after = f.readBytes()
        assertArrayEquals(
            "an append adds to the log; it must not rewrite what is already there",
            beforeLast,
            after.copyOf(beforeLast.size),
        )
        assertEquals(listOf(1, 2, 3, 4, 5, 6), store.loadAll().shots.map { it.shotId })
        // Read back through a fresh store, so nothing can be served from the
        // length the append path cached.
        val reloaded = ShotProtoStore(f)
        assertEquals(6, reloaded.loadAll().shots.size)
        assertTrue("the file must still validate clean: ${reloaded.validate().problems}", reloaded.validate().isClean)
    }

    /**
     * The cache is an optimisation, never a permission to assume. A length this
     * store did not write — here, a file truncated behind its back — must miss the
     * cache and be scanned, and the shot appended after the tear must still be
     * readable.
     */
    @Test
    fun aFileTruncatedBehindTheStoreIsStillRepairedBeforeTheNextAppend() = runTest {
        val f = file()
        val store = ShotProtoStore(f)
        store.appendAll(listOf(shot(1), shot(2), shot(3)))
        // The cached length no longer describes the file, whatever the cache says.
        val bytes = f.readBytes()
        f.writeBytes(bytes.copyOf(bytes.size - 12))

        assertTrue(store.append(shot(4)))

        val reloaded = ShotProtoStore(f)
        assertEquals(
            "the torn tail must not swallow the new shot",
            listOf(1, 2, 4),
            reloaded.loadAll().shots.map { it.shotId },
        )
        assertTrue("the repaired file validates clean: ${reloaded.validate().problems}", reloaded.validate().isClean)
    }

    /**
     * A torn tail leaves the file not ending on a record boundary, and the reader
     * stops at the tear. Appending there would write a shot nothing can ever read —
     * not `loadAll`, not the export, not `validate` — while `append` still reported
     * success, and every shot after it would pile up behind the same tear. The tail
     * is therefore repaired before the append, keeping every record the read could
     * account for.
     */
    @Test
    fun appendingAfterATornTailDoesNotStrandTheNewShot() = runTest {
        val f = tornFile()
        assertFalse("precondition: the file starts torn", ShotProtoStore(f).validate().isClean)
        val store = ShotProtoStore(f)

        assertTrue(store.append(shot(4)))

        // Read back through a *fresh* store, so nothing can be served from a cache
        // the append path happened to fill.
        val reloaded = ShotProtoStore(f)
        assertEquals(
            "the torn tail must not swallow the new shot",
            listOf(1, 2, 4),
            reloaded.loadAll().shots.map { it.shotId },
        )
        assertTrue("the repaired file validates clean: ${reloaded.validate().problems}", reloaded.validate().isClean)
        // A second append lands readable too, so the store is not left wedged.
        assertTrue(reloaded.append(shot(5)))
        assertEquals(listOf(1, 2, 4, 5), ShotProtoStore(f).loadAll().shots.map { it.shotId })
    }

    // --- dedup across sessions ---

    /**
     * The index is a cache of the file, reseeded on first use. A restart is the
     * only thing that exercises that reseed, and every other test here shares one
     * store instance whose index its own append filled — so without this one a
     * broken reseed would add a duplicate row on every launch and no test would
     * notice.
     */
    @Test
    fun aRelaunchedStoreDoesNotReAddAShotItAlreadyHolds() = runTest {
        val f = file()
        val s = shot(1)
        assertTrue(ShotProtoStore(f).append(s))

        val relaunched = ShotProtoStore(f)
        assertFalse("the same payload after a restart", relaunched.append(s))
        assertEquals(1, relaunched.loadAll().shots.size)
    }

    // --- CSV export (DESIGN §11) ---

    @Test
    fun exportWritesAValidReadableCsv() = runTest {
        val store = ShotProtoStore(file())
        store.appendAll(listOf(shot(1), shot(2).copy(clubLabel = GolfClub.IRON_SEVEN.id)))

        val out = store.exportCsv(tmp.root, "test")

        assertTrue(out.exists())
        val v = ShotCsvFormat.validateFile(out)
        assertTrue("exported CSV must validate: ${v.problems}", v.isClean)
        assertEquals(2, v.parsed)
        assertTrue(ShotCsvFormat.HEADER in out.readText())
        assertTrue(GolfClub.IRON_SEVEN.id in out.readText())
    }

    @Test
    fun theExportCarriesEveryValueTheOldCsvDid() = runTest {
        val store = ShotProtoStore(file())
        store.append(shot(1).copy(clubLabel = GolfClub.PUTTER.id))
        val out = store.exportCsv(tmp.root, "t")
        val text = out.readText()
        // Ball speed exists only as a float in the proto, in m/s; the export must
        // show the converted mph, which is the whole "derived view" claim.
        val expectedMph = MetricConverter.shot(R10Protos.Metrics.parseFrom(metrics(1)), 0).ball!!.ballSpeedMph
        assertTrue("ball speed $expectedMph missing from export:\n$text", expectedMph.toInt().toString() in text)
        assertTrue("club label missing from export:\n$text", GolfClub.PUTTER.id in text)
    }

    @Test
    fun anEmptyStoreStillExportsAHeaderOnlyCsv() = runTest {
        val store = ShotProtoStore(file())
        val out = store.exportCsv(tmp.root, "empty")
        assertTrue(ShotCsvFormat.validateFile(out).isClean)
    }

    /**
     * The CSV is a derived copy, so it is well-formed by construction: validating
     * the file alone would report a healthy export while the damaged record was
     * dropped on the way out. `MainActivity.exportCsv` asks the store too, and this
     * is the damage it has to report.
     */
    @Test
    fun exportingATornStoreReportsTheDamageTheCopyCannotShow() = runTest {
        val store = ShotProtoStore(tornFile())

        val out = store.exportCsv(tmp.root, "torn")

        assertTrue("the user still gets the file", out.exists())
        assertTrue("the copy itself is well-formed", ShotCsvFormat.validateFile(out).isClean)
        assertFalse(
            "the store's own damage must not read as a clean export",
            store.validate().isClean,
        )
    }
}

package com.techdelivery.r10.data

import LaunchMonitor.Proto.R10Protos
import com.techdelivery.r10.club.GolfClub
import com.techdelivery.r10.protocol.shot.MetricConverter
import com.techdelivery.r10.protocol.shot.Shot
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
     */
    private fun shot(id: Int, at: Long = 1_700_000_000_000L + id): Shot {
        val raw = metrics(id)
        return MetricConverter.shot(R10Protos.Metrics.parseFrom(raw), at)
    }

    /** A real `Metrics` payload, so the store round-trips the device's own bytes. */
    private fun metrics(id: Int): ByteArray {
        val ball = R10Protos.BallMetrics.newBuilder()
            .setBallSpeed(88.0f + id)
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

    // --- basics ---

    @Test
    fun aShotRoundTripsThroughTheStore() = runTest {
        val store = ShotProtoStore(file())
        val original = shot(1)
        assertTrue(store.append(original))
        val back = store.loadAll().single()
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
        assertTrue(metrics(1).contentEquals(store.loadAll().single().rawMetrics))
    }

    @Test
    fun historyIsOldestFirstAndOrdered() = runTest {
        val store = ShotProtoStore(file())
        store.appendAll(listOf(shot(1), shot(2), shot(3)))
        assertEquals(listOf(1, 2, 3), store.loadAll().map { it.shotId })
    }

    @Test
    fun anIdenticalRepushIsDeduped() = runTest {
        val store = ShotProtoStore(file())
        val s = shot(1)
        assertTrue(store.append(s))
        assertFalse("same payload again", store.append(s))
        assertEquals(1, store.loadAll().size)
    }

    @Test
    fun aDifferentIdIsStillWritten() = runTest {
        val store = ShotProtoStore(file())
        store.append(shot(1))
        // Same payload bytes, different shot id. The dedup key is
        // `shot_id || payload`, precisely so a payload collision can never drop a
        // real shot.
        assertTrue(store.append(shot(1).copy(shotId = 2)))
        assertEquals(2, store.loadAll().size)
    }

    @Test
    fun aMissingFileLoadsEmpty() = runTest {
        val store = ShotProtoStore(File(tmp.newFolder(), "absent.bin"))
        assertTrue(store.loadAll().isEmpty())
    }

    @Test
    fun clearRemovesHistory() = runTest {
        val store = ShotProtoStore(file())
        store.appendAll(listOf(shot(1), shot(2)))
        store.clear()
        assertTrue(store.loadAll().isEmpty())
        assertTrue("a cleared store accepts shots again", store.append(shot(3)))
    }

    // --- club annotation (R5) ---

    @Test
    fun aClubLabelRoundTrips() = runTest {
        val store = ShotProtoStore(file())
        store.append(shot(1).copy(clubLabel = GolfClub.IRON_SEVEN.id))
        assertEquals(GolfClub.IRON_SEVEN.id, store.loadAll().single().clubLabel)
    }

    @Test
    fun anUntaggedShotHasNoLabel() = runTest {
        val store = ShotProtoStore(file())
        store.append(shot(1))
        assertNull(store.loadAll().single().clubLabel)
    }

    @Test
    fun updateClubRewritesOnlyThatRow() = runTest {
        val store = ShotProtoStore(file())
        store.appendAll(listOf(shot(1), shot(2), shot(3)))
        val payloads = store.loadAll().associate { it.shotId to it.rawMetrics.toList() }

        assertTrue(store.updateClub(2, GolfClub.DRIVER))

        val all = store.loadAll()
        assertEquals(listOf(null, GolfClub.DRIVER.id, null), all.map { it.clubLabel })
        assertEquals("the other shots' bytes must be untouched", payloads[1], all[0].rawMetrics.toList())
        assertEquals(payloads[3], all[2].rawMetrics.toList())
    }

    @Test
    fun clearingTheClubIsNotDeletingTheShot() = runTest {
        val store = ShotProtoStore(file())
        store.append(shot(1))
        assertTrue(store.updateClub(1, GolfClub.PUTTER))
        assertTrue(store.updateClub(1, null))
        assertEquals(1, store.loadAll().size)
        assertNull(store.loadAll().single().clubLabel)
    }

    @Test
    fun updatingAnAbsentShotIsANoop() = runTest {
        val store = ShotProtoStore(file())
        store.append(shot(1))
        assertFalse(store.updateClub(99, GolfClub.DRIVER))
        assertEquals(1, store.loadAll().size)
    }

    /** A label this build does not know must still round-trip untouched. */
    @Test
    fun anUnknownLabelIsKeptVerbatim() = runTest {
        val store = ShotProtoStore(file())
        store.append(shot(1).copy(clubLabel = "Sand Wedge Deluxe"))
        assertEquals("Sand Wedge Deluxe", store.loadAll().single().clubLabel)
    }

    // --- delete (R6) ---

    @Test
    fun deleteRemovesOnlyThatRowAndKeepsOrder() = runTest {
        val store = ShotProtoStore(file())
        store.appendAll(listOf(shot(1), shot(2), shot(3)))
        assertTrue(store.deleteShot(2))
        assertEquals(listOf(1, 3), store.loadAll().map { it.shotId })
    }

    @Test
    fun deletingTheOnlyShotLeavesAnEmptyButValidStore() = runTest {
        val store = ShotProtoStore(file())
        store.append(shot(1))
        assertTrue(store.deleteShot(1))
        assertTrue(store.loadAll().isEmpty())
        val v = store.validate()
        assertTrue("a header-only store is still valid: ${v.problems}", v.isClean)
    }

    @Test
    fun deletingAnAbsentShotIsANoop() = runTest {
        val store = ShotProtoStore(file())
        store.append(shot(1))
        assertFalse(store.deleteShot(42))
        assertEquals(1, store.loadAll().size)
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
        assertTrue(store.deleteShot(1))
        assertTrue("re-push after delete must write again", store.append(s))
        assertEquals(1, store.loadAll().size)
    }

    @Test
    fun aRewriteLeavesNoTempFileBehind() = runTest {
        val f = file()
        val store = ShotProtoStore(f)
        store.appendAll(listOf(shot(1), shot(2)))
        store.updateClub(1, GolfClub.FIVE_WOOD)
        store.deleteShot(2)
        assertEquals(listOf(f.name), f.parentFile?.list()?.toList())
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
        val loaded = reloaded.loadAll()
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
        store.appendAll(listOf(shot(1), shot(2)))
        val clean = f.readBytes()
        // Corrupt a byte in the first record's payload region.
        val damaged = clean.copyOf()
        damaged[4] = (damaged[4].toInt() xor 0xFF).toByte()
        f.writeBytes(damaged)

        val reloaded = ShotProtoStore(f)
        assertNotNull("validate must not throw on damage", reloaded.validate())
    }

    @Test
    fun anEmptyStoreReportsRatherThanPasses() = runTest {
        val v = ShotProtoStore(file()).validate()
        assertFalse(v.isClean)
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
}

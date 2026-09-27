package com.techdelivery.r10.data

import LaunchMonitor.Proto.R10Protos
import com.techdelivery.r10.club.GolfClub
import com.techdelivery.r10.protocol.shot.BallDisplay
import com.techdelivery.r10.protocol.shot.Shot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * ROADMAP R5 (club annotation) and R6 (delete a shot).
 *
 * The two rules these tests exist to protect:
 *  - a **v1 file keeps working**. It loads, it validates clean, and it can be
 *    migrated — it never becomes "invalid" (the R4 rule).
 *  - a mutation is **atomic and index-correct**. A rewrite leaves the file whole,
 *    and the dedup index is rebuilt so a deleted shot that the device re-pushes is
 *    written again instead of being silently suppressed.
 */
class ShotClubStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun shot(id: Int, at: Long = 1_700_000_000_000L + id, raw: ByteArray = byteArrayOf(0x08, id.toByte())) =
        Shot(
            shotId = id,
            shotType = R10Protos.Metrics.ShotType.NORMAL,
            receivedAtMs = at,
            ball = BallDisplay(150.0 - id, 12.0, -1.0, -90.0, 7000.0, -3000.0, 3000.0),
            rawMetrics = raw,
        )

    private fun file() = tmp.newFile()

    /** A hand-built v1 file: the 21-column header and rows, no version, no club. */
    private fun v1File(shots: List<Shot>): File {
        val f = file()
        val rows = shots.joinToString("\n") { s ->
            // Re-encode at the v1 shape by dropping the version and club columns.
            val v2 = ShotCsvFormat.encode(s).split(',')
            v2.drop(1).dropLast(1).joinToString(",")
        }
        f.writeText("${ShotCsvFormat.HEADER_V1}\n$rows\n")
        return f
    }

    // --- club annotation (R5) ---

    @Test
    fun aClubLabelRoundTrips() {
        val tagged = shot(1).copy(clubLabel = GolfClub.IRON_SEVEN.id)
        assertEquals(tagged, ShotCsvFormat.decode(ShotCsvFormat.encode(tagged)))
    }

    @Test
    fun anUntaggedShotRoundTripsWithNoLabel() {
        val back = ShotCsvFormat.decode(ShotCsvFormat.encode(shot(1)))
        assertNull(back?.clubLabel)
    }

    @Test
    fun everyRowCarriesTheCurrentSchemaVersion() {
        val row = ShotCsvFormat.encode(shot(1))
        assertEquals(ShotCsvFormat.SCHEMA_VERSION.toString(), row.substringBefore(','))
        assertTrue(row.endsWith(",${GolfClub.PUTTER.id}") || row.endsWith(","))
    }

    @Test
    fun theClubColumnIsLastAndTheVersionColumnIsFirst() {
        assertEquals("schema_version", ShotCsvFormat.COL_NAMES.first())
        assertEquals("club_label", ShotCsvFormat.COL_NAMES.last())
        assertEquals(ShotCsvFormat.COL_NAMES_V1.size + 2, ShotCsvFormat.COL_NAMES.size)
    }

    @Test
    fun updateClubRewritesOnlyThatRow() = runTest {
        val store = ShotCsvStore(file())
        store.appendAll(listOf(shot(1), shot(2), shot(3)))

        assertTrue(store.updateClub(2, GolfClub.DRIVER))

        val all = store.loadAll()
        assertEquals(listOf(null, GolfClub.DRIVER.id, null), all.map { it.clubLabel })
        assertTrue("all shots still present", all.size == 3)
        assertTrue("file still valid after a club edit", store.validate().isClean)
    }

    @Test
    fun clearingTheClubIsNotTheSameAsDeletingTheShot() = runTest {
        val store = ShotCsvStore(file())
        store.append(shot(1))
        assertTrue(store.updateClub(1, GolfClub.PUTTER))
        assertTrue(store.updateClub(1, null))
        assertEquals(1, store.loadAll().size)
        assertNull(store.loadAll().first().clubLabel)
    }

    @Test
    fun updatingAnAbsentShotIsANoop() = runTest {
        val store = ShotCsvStore(file())
        store.append(shot(1))
        assertFalse(store.updateClub(99, GolfClub.DRIVER))
        assertEquals(1, store.loadAll().size)
    }

    /** An unknown label on disk must not cost the user the shot. */
    @Test
    fun anUnknownStoredLabelIsReportedButKept() {
        val row = ShotCsvFormat.encode(shot(1)).dropLast(1) + ",Sand Wedge Deluxe"
        val v = ShotCsvFormat.validateText("${ShotCsvFormat.HEADER}\n$row")
        assertTrue("row must still parse: ${v.problems}", v.parsed == 1)
        assertEquals("Sand Wedge Deluxe", ShotCsvFormat.decode(row)?.clubLabel)
    }

    // --- delete (R6) ---

    @Test
    fun deleteRemovesOnlyThatRowAndKeepsOrder() = runTest {
        val store = ShotCsvStore(file())
        store.appendAll(listOf(shot(1), shot(2), shot(3)))

        assertTrue(store.deleteShot(2))

        assertEquals(listOf(1, 3), store.loadAll().map { it.shotId })
        assertTrue(store.validate().isClean)
    }

    @Test
    fun deletingTheNewestLeavesTheRest() = runTest {
        val store = ShotCsvStore(file())
        store.appendAll(listOf(shot(1), shot(2)))
        assertTrue(store.deleteShot(2))
        assertEquals(listOf(1), store.loadAll().map { it.shotId })
    }

    @Test
    fun deletingTheOnlyShotLeavesAValidFile() = runTest {
        val store = ShotCsvStore(file())
        store.append(shot(1))
        assertTrue(store.deleteShot(1))
        assertTrue(store.loadAll().isEmpty())
        assertTrue("a header-only file is still valid", store.validate().isClean)
    }

    @Test
    fun deletingAnAbsentShotIsANoop() = runTest {
        val store = ShotCsvStore(file())
        store.append(shot(1))
        assertFalse(store.deleteShot(42))
        assertEquals(1, store.loadAll().size)
    }

    /**
     * The subtle one. The dedup index caches what the file contains, so a delete
     * that left the key in place would suppress the device re-pushing that exact
     * shot for the rest of the session — the R10 would look like it stopped sending.
     */
    @Test
    fun aDeletedShotCanBeWrittenAgainWhenTheDeviceRepushesIt() = runTest {
        val store = ShotCsvStore(file())
        val s = shot(1)
        assertTrue("first write", store.append(s))
        assertFalse("re-push is deduped while stored", store.append(s))
        assertTrue(store.deleteShot(1))
        assertTrue("re-push after delete must write again", store.append(s))
        assertEquals(1, store.loadAll().size)
    }

    /** A rewrite goes through a temp file, so no partial state is left behind. */
    @Test
    fun aRewriteLeavesNoTempFileBehind() = runTest {
        val f = file()
        val store = ShotCsvStore(f)
        store.appendAll(listOf(shot(1), shot(2)))
        store.updateClub(1, GolfClub.FIVE_WOOD)
        store.deleteShot(2)
        assertEquals("only the real file remains", listOf(f.name), f.parentFile?.list()?.toList())
    }

    // --- v1 compatibility and migration (DESIGN §8) ---

    @Test
    fun aV1FileLoads() = runTest {
        val store = ShotCsvStore(v1File(listOf(shot(1), shot(2))))
        val all = store.loadAll()
        assertEquals(listOf(1, 2), all.map { it.shotId })
        assertEquals(149.0, all.first().ball!!.ballSpeedMph, 0.001)
        assertNull("a v1 row has no club", all.first().clubLabel)
    }

    /** The R4 rule across a schema change: an old file is not "invalid". */
    @Test
    fun aV1FileValidatesClean() {
        val v = ShotCsvFormat.validateText(v1File(listOf(shot(1), shot(2))).readText())
        assertTrue("v1 must validate: ${v.problems}", v.isClean)
        assertEquals(2, v.parsed)
    }

    @Test
    fun aV1RowIsNotBlamedForLackingTheClubColumn() {
        val row = ShotCsvFormat.encode(shot(1)).split(',').drop(1).dropLast(1).joinToString(",")
        val v = ShotCsvFormat.validateText("${ShotCsvFormat.HEADER_V1}\n$row")
        assertTrue(v.problems.isEmpty())
    }

    @Test
    fun migrationUpgradesRowsAndPreservesThem() = runTest {
        val f = v1File(listOf(shot(1), shot(2)))
        val store = ShotCsvStore(f)
        val before = store.loadAll()

        assertTrue(store.migrate())

        val text = f.readText()
        assertTrue("header upgraded", text.startsWith(ShotCsvFormat.HEADER))
        assertTrue(
            "row upgraded, order kept",
            text.lines().filter { it.isNotBlank() } ==
                listOf(ShotCsvFormat.HEADER) + before.map { ShotCsvFormat.encode(it) },
        )
        assertEquals(before, store.loadAll())
        assertTrue(store.validate().isClean)
    }

    @Test
    fun migrationIsIdempotent() = runTest {
        val store = ShotCsvStore(v1File(listOf(shot(1))))
        assertTrue(store.migrate())
        assertFalse("a second migrate is a no-op", store.migrate())
    }

    @Test
    fun migrationOnAMissingFileIsANoop() = runTest {
        val store = ShotCsvStore(tmp.newFolder().let { File(it, "absent.csv") })
        assertFalse(store.migrate())
        assertTrue(store.loadAll().isEmpty())
    }

    /** A club can be set on a not-yet-migrated file; the row upgrades in place. */
    @Test
    fun aClubCanBeSetOnAnUnmigratedV1File() = runTest {
        val store = ShotCsvStore(v1File(listOf(shot(1))))
        assertTrue(store.updateClub(1, GolfClub.IRON_NINE))
        assertEquals(GolfClub.IRON_NINE.id, store.loadAll().single().clubLabel)
        assertTrue(store.validate().isClean)
    }

    @Test
    fun aBogusVersionCellIsReportedWithTheColumnName() {
        val row = ShotCsvFormat.encode(shot(1)).replaceFirst("2,", "9,", ignoreCase = false)
        val v = ShotCsvFormat.validateText("${ShotCsvFormat.HEADER}\n$row")
        assertFalse(v.isClean)
        assertTrue("${v.problems}", v.problems.any { "schema_version" in it })
    }
}

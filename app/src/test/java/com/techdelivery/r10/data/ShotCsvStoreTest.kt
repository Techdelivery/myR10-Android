package com.techdelivery.r10.data

import LaunchMonitor.Proto.R10Protos
import com.techdelivery.r10.protocol.shot.BallDisplay
import com.techdelivery.r10.protocol.shot.ClubDisplay
import com.techdelivery.r10.protocol.shot.Shot
import com.techdelivery.r10.protocol.shot.SwingDisplay
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
}

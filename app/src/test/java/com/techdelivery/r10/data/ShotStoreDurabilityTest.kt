package com.techdelivery.r10.data

import LaunchMonitor.Proto.R10Protos
import com.techdelivery.r10.club.GolfClub
import com.techdelivery.r10.protocol.shot.MetricConverter
import com.techdelivery.r10.protocol.shot.Shot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What a rewrite has to survive, as opposed to what it may change
 * (`ShotRecordSpliceTest`).
 *
 * Split out of `ShotProtoStoreTest` for the same reason and because it is a
 * different question: not which fields survive, but what happens when the write
 * itself does not land. ROADMAP parked item D — the rename-failure branch was the
 * last thing in the store verified by reading alone.
 */
class ShotStoreDurabilityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun shot(id: Int, at: Long = 1_700_000_000_000L + id): Shot {
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
        val raw = R10Protos.Metrics.newBuilder()
            .setShotId(id)
            .setShotType(R10Protos.Metrics.ShotType.NORMAL)
            .setBallMetrics(ball)
            .setClubMetrics(club)
            .build()
            .toByteArray()
        return MetricConverter.shot(R10Protos.Metrics.parseFrom(raw), at)
    }

    private fun file() = tmp.newFile()

    /**
     * The last branch in the store that was verified by reading alone (ROADMAP
     * parked D): a rewrite whose rename fails.
     *
     * The throw is the only thing standing between a failed rewrite and a store
     * that believes it succeeded, and the KDoc's promise — the previous file plus
     * the untouched temp are both still on disk — was asserted by nobody. The
     * rename is injected because there is no portable way to fail it otherwise: a
     * read-only directory is ignored when the tests happen to run as root, and
     * making the destination a directory fails in the *read* instead.
     *
     * Note that the injected lambda still *performs* the rename. The seam answers
     * "did this succeed", and a first version of this test short-circuited it to a
     * bare `true` — which left the store believing it had replaced the file while
     * nothing had moved, and a later assertion passed for the wrong reason. The
     * seam controls the failure, never the effect.
     */
    @Test
    fun aFailedRenameRefusesTheWriteAndLeavesTheOriginalIntact() = runTest {
        val f = file()
        var failRename = false
        val store = ShotProtoStore(f, rename = { source, target -> !failRename && source.renameTo(target) })
        store.appendAll(listOf(shot(1), shot(2)))
        val before = f.readBytes()
        failRename = true

        val failure = runCatching { store.updateClub(1, shot(1).receivedAtMs, GolfClub.DRIVER) }

        assertTrue("a failed rename must be reported, not swallowed", failure.isFailure)
        assertArrayEquals("the previous file must be untouched", before, f.readBytes())
        assertTrue(
            "the temp file is left for the next attempt",
            File(f.parentFile, "${f.name}.tmp").exists(),
        )
        assertEquals("the history still reads", listOf(1, 2), store.loadAll().shots.map { it.shotId })
    }

    /**
     * The part that is not written down anywhere: a refused rewrite must leave the
     * store able to make progress. A throw that bricked the store would turn one
     * transient failure into permanent data loss, which is the opposite of what the
     * branch is for.
     */
    @Test
    fun aStoreStillWritesAfterAFailedRename() = runTest {
        val f = file()
        var failRename = false
        val store = ShotProtoStore(f, rename = { source, target -> !failRename && source.renameTo(target) })
        store.appendAll(listOf(shot(1), shot(2)))
        failRename = true
        runCatching { store.updateClub(1, shot(1).receivedAtMs, GolfClub.DRIVER) }

        // The rename works again, and both an append and a later rewrite succeed.
        failRename = false
        assertTrue("appending must still work", store.append(shot(3)))
        assertEquals(WriteOutcome.WRITTEN, store.updateClub(1, shot(1).receivedAtMs, GolfClub.DRIVER))
        println("DBG after-update bytes=" + f.readBytes().joinToString("") { "%02X".format(it) })
        val back = store.loadAll().shots
        assertEquals(listOf(1, 2, 3), back.map { it.shotId })
        val recs = DelimitedRecords.read(f.readBytes())
        val spliced = ShotRecordCodec.withClubLabel(recs[1], "D")
        println(
            "DBG splicedShot=" + ShotRecordCodec.toShot(
                DelimitedRecords.body(spliced)!!.let {
                    LaunchMonitor.Proto.R10Protos.StoredShot.parseFrom(it)
                },
            )?.clubLabel,
        )
        val fresh = ShotProtoStore(f)
        println("DBG fresh=" + fresh.loadAll().shots.map { it.shotId.toString() + ":" + it.clubLabel })
        println("DBG bytes=" + f.readBytes().joinToString("") { "%02X".format(it) })
        assertEquals("the tag landed on the shot it was aimed at", GolfClub.DRIVER.id, back.first().clubLabel)
        assertEquals(
            "a successful rewrite must clean the temp file up",
            listOf(f.name),
            f.parentFile?.list()?.toList(),
        )
    }
}

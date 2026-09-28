package com.techdelivery.r10.data

import LaunchMonitor.Proto.R10Protos
import com.techdelivery.r10.club.GolfClub
import com.techdelivery.r10.protocol.shot.MetricConverter
import com.techdelivery.r10.protocol.shot.Shot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What a rewrite is allowed to destroy, and what it is not.
 *
 * Split out of `ShotProtoStoreTest` because it is one question, and the store's own
 * file had grown past what the style gate allows a single class to be.
 *
 * The answer this pins: a rewrite copies records and edits one field in them. It must
 * never rebuild a record from its known parts, because protobuf-lite discards unknown
 * fields on parse — so re-encoding silently deleted any field a future version might
 * have added, at the moment the user tagged a club. `deleteShot` was always safe; a
 * club tag was not. DESIGN §8 promises the opposite of what the code did.
 */
class ShotRecordSpliceTest {

    @get:Rule
    val tmp = TemporaryFolder()

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

    private fun shot(id: Int, at: Long = 1_700_000_000_000L + id, salt: Int = 0): Shot =
        MetricConverter.shot(R10Protos.Metrics.parseFrom(metrics(id, salt)), at)

    private fun file() = tmp.newFile()

    /** True when [needle] appears in this byte array byte for byte. */
    private fun ByteArray.containsBytes(needle: ByteArray): Boolean =
        indices.any { i -> needle.indices.all { j -> this[i + j] == needle[j] } }

    // --- helpers that inject a field no message in this build has ---

    private fun varintSize(value: Int): Int {
        var remaining = value.toLong()
        var size = 1
        while (remaining >= 0x7F) {
            remaining = remaining shr 7
            size++
        }
        return size
    }

    /**
     * Put [unknown] at the end of the last record's body, re-encoding the varint
     * length around it so the framing stays valid. Hand-rolled rather than through
     * `DelimitedRecords.encode`, which needs a message type — and the whole point
     * here is a field no generated class has.
     */
    private fun appendUnknownFieldToLastRecord(f: File, unknown: ByteArray): ByteArray {
        val bytes = f.readBytes()
        val last = DelimitedRecords.read(bytes).last()
        val body = DelimitedRecords.body(last)!! + unknown

        val prefix = ByteArray(varintSize(body.size))
        var i = 0
        var value = body.size.toLong()
        while (true) {
            val part = (value and 0x7F).toInt()
            value = value shr 7
            prefix[i++] = if (value == 0L) part.toByte() else (part or 0x80).toByte()
            if (value == 0L) break
        }
        return bytes.copyOfRange(0, bytes.size - last.size) + prefix + body
    }

    /**
     * Put [unknown] inside the last record's nested `metrics` submessage — the other
     * half of the claim, since the device's own message is what a future build would
     * most likely extend.
     */
    private fun appendUnknownFieldInsideMetrics(f: File, unknown: ByteArray): ByteArray {
        val bytes = f.readBytes()
        val last = DelimitedRecords.read(bytes).last()
        val body = DelimitedRecords.body(last)!!

        val rebuilt = ArrayList<ByteArray>()
        var pos = 0
        while (pos < body.size) {
            val start = pos
            val tag = DelimitedRecords.readVarint(body, pos)!!
            pos += tag.size
            val number = (tag.value ushr 3).toInt()
            val isDelimited = (tag.value and 7L) == 2L
            val len = if (isDelimited) DelimitedRecords.readVarint(body, pos)!! else null
            pos += if (isDelimited) {
                len!!.size + len.value.toInt()
            } else {
                DelimitedRecords.readVarint(body, pos)!!.size
            }
            val raw = body.copyOfRange(start, pos)
            rebuilt.add(
                if (number == metricsField) {
                    // The payload sits after this field's own tag and length, so it is
                    // sliced here rather than through `DelimitedRecords.body`, which
                    // expects a record to start with a length.
                    val payloadStart = start + tag.size + len!!.size
                    val inner = body.copyOfRange(payloadStart, pos) + unknown
                    DelimitedRecords.varint((metricsField.toLong() shl 3) or 2L) +
                        DelimitedRecords.varint(inner.size.toLong()) + inner
                } else {
                    raw
                },
            )
        }
        val newBody = rebuilt.reduce { a, b -> a + b }
        return bytes.copyOfRange(0, bytes.size - last.size) + DelimitedRecords.encodeBody(newBody)
    }

    private val metricsField = 3

    // --- the tests ---

    /**
     * The whole point: the device's bytes come back byte-identical.
     *
     * Checked again, harder, by [anUnknownFieldSurvivesARewrite] — "byte-identical"
     * was only ever proven for a message this build fully understands.
     */
    @Test
    fun theDevicePayloadIsPreservedExactly() = runTest {
        val store = ShotProtoStore(file())
        store.append(shot(1))
        assertTrue(metrics(1).contentEquals(store.loadAll().shots.single().rawMetrics))
    }

    /**
     * The stronger claim, and the one the format actually rests on: a field this
     * build does not know about survives a rewrite.
     *
     * This is what ROADMAP parked item F predicted, and it was a real bug rather
     * than a loose assertion. A club tag used to decode the record and encode it
     * again, and protobuf-lite discards unknown fields on parse — so tagging a club
     * deleted every field a future version might have added, silently, at the moment
     * the user picked a club. `deleteShot` was never affected: it copies the records
     * it keeps verbatim, and [anUnknownFieldSurvivesADelete] is the control.
     *
     * Asserted on the raw file bytes rather than a parsed object, because a parsed
     * object is exactly what cannot see the difference.
     */
    @Test
    fun anUnknownFieldSurvivesARewrite() = runTest {
        val f = file()
        val store = ShotProtoStore(f)
        store.append(shot(1))
        // Field 99, varint, value 7. No message this build knows has that field.
        val unknown = byteArrayOf(0x98.toByte(), 0x06, 0x07)
        f.writeBytes(appendUnknownFieldToLastRecord(f, unknown))
        assertTrue("precondition: the field is in the file", f.readBytes().containsBytes(unknown))

        assertEquals(WriteOutcome.WRITTEN, store.updateClub(1, shot(1).receivedAtMs, GolfClub.DRIVER))

        val after = f.readBytes()
        assertTrue(
            "the unknown field was dropped by the rewrite",
            after.containsBytes(unknown),
        )
        assertEquals(1, store.loadAll().shots.size)
        assertEquals(GolfClub.DRIVER.id, store.loadAll().shots.single().clubLabel)
    }

    /** An unknown field inside the device's own `Metrics`, not on the wrapper. */
    @Test
    fun anUnknownFieldInsideTheDevicePayloadSurvivesATag() = runTest {
        val f = file()
        val store = ShotProtoStore(f)
        store.append(shot(1))
        val unknown = byteArrayOf(0x98.toByte(), 0x06, 0x07)
        f.writeBytes(appendUnknownFieldInsideMetrics(f, unknown))
        assertTrue("precondition: the field is inside the metrics", f.readBytes().containsBytes(unknown))

        assertEquals(WriteOutcome.WRITTEN, store.updateClub(1, shot(1).receivedAtMs, GolfClub.DRIVER))

        val back = store.loadAll().shots.single()
        assertEquals(GolfClub.DRIVER.id, back.clubLabel)
        assertTrue(
            "the device's own bytes were re-shaped, not spliced",
            back.rawMetrics.containsBytes(unknown),
        )
    }

    /** Clearing is the same splice with nothing to append, so it preserves too. */
    @Test
    fun anUnknownFieldSurvivesClearingTheClub() = runTest {
        val f = file()
        val store = ShotProtoStore(f)
        store.append(shot(1).copy(clubLabel = GolfClub.DRIVER.id))
        val unknown = byteArrayOf(0x98.toByte(), 0x06, 0x07)
        f.writeBytes(appendUnknownFieldToLastRecord(f, unknown))

        assertEquals(WriteOutcome.WRITTEN, store.updateClub(1, shot(1).receivedAtMs, null))

        assertTrue("clearing is a rewrite too", f.readBytes().containsBytes(unknown))
        assertNull(store.loadAll().shots.single().clubLabel)
    }

    /** Re-tagging replaces the field rather than stacking copies of it. */
    @Test
    fun reTaggingReplacesTheClubLabelRatherThanStackingThem() = runTest {
        val store = ShotProtoStore(file())
        store.append(shot(1))
        val at = shot(1).receivedAtMs

        assertEquals(WriteOutcome.WRITTEN, store.updateClub(1, at, GolfClub.DRIVER))
        assertEquals(WriteOutcome.WRITTEN, store.updateClub(1, at, GolfClub.PUTTER))

        assertEquals(GolfClub.PUTTER.id, store.loadAll().shots.single().clubLabel)
    }

    /**
     * A body the splicer cannot walk is refused rather than half-rewritten. Protobuf
     * groups (wire types 3 and 4) are the case: legal in the format, used by no
     * message here, and a rewrite that did not understand one would emit a record
     * that no longer parses.
     */
    @Test
    fun aRecordTheSplicerCannotWalkIsRefusedRatherThanMangled() = runTest {
        val framed = ShotRecordCodec.encode(shot(1))
        val body = DelimitedRecords.body(framed)!!
        // Start-group tag for field 40 (0xA3), appended to an otherwise valid body.
        val withGroup = DelimitedRecords.encodeBody(body + byteArrayOf(0xA3.toByte()))

        val failure = runCatching { ShotRecordCodec.withClubLabel(withGroup, GolfClub.DRIVER.id) }
        assertTrue("an unsupported wire type must be refused", failure.isFailure)
    }

    /**
     * Delete copies the records it keeps verbatim, so it is already lossless — this
     * is the control for [anUnknownFieldSurvivesARewrite], and the reason that test's
     * failure is about the club tag and not about rewrites in general.
     */
    @Test
    fun anUnknownFieldSurvivesADelete() = runTest {
        val f = file()
        val store = ShotProtoStore(f)
        store.appendAll(listOf(shot(1), shot(2), shot(3)))
        // The field goes on the last record, and the *middle* one is the one deleted,
        // so what is checked is a surviving record rather than the deleted one.
        val unknown = byteArrayOf(0x98.toByte(), 0x06, 0x07)
        f.writeBytes(appendUnknownFieldToLastRecord(f, unknown))

        assertEquals(WriteOutcome.WRITTEN, store.deleteShot(2, shot(2).receivedAtMs))

        assertTrue("a delete copies records, it does not re-encode them", f.readBytes().containsBytes(unknown))
        assertEquals(listOf(1, 3), store.loadAll().shots.map { it.shotId })
    }
}

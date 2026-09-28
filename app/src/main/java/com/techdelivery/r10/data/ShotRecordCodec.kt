package com.techdelivery.r10.data

import LaunchMonitor.Proto.R10Protos
import com.techdelivery.r10.club.GolfClub
import com.techdelivery.r10.protocol.shot.MetricConverter
import com.techdelivery.r10.protocol.shot.Shot

/**
 * Translation between a [Shot] and the record kept on disk (DESIGN §8, ROADMAP R7).
 *
 * One direction stores what the device sent — the `Metrics` message, byte for byte —
 * and adds only the two facts the app owns: arrival time, and the club the user
 * picked. The other recomputes every displayed value through [MetricConverter], the
 * same conversion that ran when the shot arrived. That is why the store cannot lose
 * precision to text formatting, and why a field added to `Metrics` later appears
 * without a schema change: the bytes were never re-shaped in the first place.
 */
internal object ShotRecordCodec {

    /** The header record that opens a shot log. */
    fun header(storeFormatVersion: Int): ByteArray = DelimitedRecords.encode(
        R10Protos.ShotLogHeader.newBuilder()
            .setStoreFormatVersion(storeFormatVersion)
            .build(),
    )

    /** A framed record for [shot]. */
    fun encode(shot: Shot): ByteArray {
        val metrics = runCatching { R10Protos.Metrics.parseFrom(shot.rawMetrics) }.getOrNull()
            ?: R10Protos.Metrics.getDefaultInstance()
        val builder = R10Protos.StoredShot.newBuilder()
            .setReceivedAtMs(shot.receivedAtMs)
            .setMetrics(metrics)
        shot.clubLabel?.let { builder.setClubLabel(it) }
        return DelimitedRecords.encode(builder.build())
    }

    /**
     * The shot a record holds, or null.
     *
     * A record with no metrics is not a shot — that is how the header record, and any
     * damaged record, is kept from being read as a shot full of zeroed numbers,
     * which would be worse than skipping it.
     */
    fun toShot(record: R10Protos.StoredShot): Shot? {
        if (!record.hasMetrics()) return null
        return runCatching {
            MetricConverter.shot(record.metrics, record.receivedAtMs)
                .copy(clubLabel = record.clubLabel.ifBlank { null })
        }.getOrNull()
    }

    /**
     * The same record with its club label set, or cleared when [clubId] is null.
     *
     * Built by **splicing the one field** rather than by decoding the record and
     * encoding it again. That distinction is the whole point: re-encoding drops every
     * field this build does not know about — protobuf-lite discards unknown fields
     * on parse — so a club tag used to delete any field added to `StoredShot` or to
     * the device's `Metrics` in a future version, and it did so silently, the first
     * time the user tagged a club. DESIGN §8 promises the opposite: that a field
     * added later appears without a schema change because the bytes are never
     * re-shaped.
     *
     * Every other field is copied as raw bytes, which also covers the nested
     * `metrics` message: it is one length-delimited run, and this never looks
     * inside it. So nothing anywhere in the record is interpreted to be preserved.
     *
     * Existing `club_label` fields are dropped and the new one appended last, so it
     * is the one that wins if the record somehow carries it twice.
     *
     * Throws [IllegalArgumentException] on a body this cannot walk — a group, or a
     * field whose length runs past the end. Refusing the tag is recoverable and is
     * reported to the user; writing back a half-rewritten record is neither.
     */
    fun withClubLabel(framed: ByteArray, clubId: String?): ByteArray {
        val body = DelimitedRecords.body(framed) ?: throw IllegalArgumentException("record is not framed")
        val kept = ArrayList<ByteArray>()
        var pos = 0
        while (pos < body.size) {
            val start = pos
            val tag = DelimitedRecords.readVarint(body, pos) ?: malformed(body, start)
            pos += tag.size
            val fieldNumber = (tag.value ushr FIELD_NUMBER_SHIFT).toInt()
            val wireType = (tag.value and WIRE_TYPE_MASK).toInt()
            pos = when (wireType) {
                WIRE_VARINT -> pos + varintAt(body, pos, start)

                WIRE_FIXED64 -> pos + FIXED64_BYTES

                WIRE_LENGTH_DELIMITED -> {
                    val len = DelimitedRecords.readVarint(body, pos) ?: malformed(body, pos)
                    pos + len.size + len.value.toInt()
                }

                WIRE_FIXED32 -> pos + FIXED32_BYTES

                else -> throw IllegalArgumentException(
                    "field $fieldNumber uses wire type $wireType, which is not supported",
                )
            }
            if (pos > body.size) malformed(body, start)
            if (fieldNumber != CLUB_LABEL_FIELD) kept.add(body.copyOfRange(start, pos))
        }
        clubId?.let {
            val value = it.toByteArray()
            kept.add(lengthDelimited(CLUB_LABEL_FIELD, value.size.toLong()) + value)
        }
        return DelimitedRecords.encodeBody(kept.reduce { a, b -> a + b })
    }

    private fun malformed(body: ByteArray, at: Int): Nothing =
        throw IllegalArgumentException("record body is malformed at byte $at of ${body.size}")

    /** The varint at [offset] must end inside the body; its size, or a refusal. */
    private fun varintAt(body: ByteArray, offset: Int, fieldStart: Int): Int =
        DelimitedRecords.readVarint(body, offset)?.size ?: malformed(body, fieldStart)

    /** A length-delimited field: tag, then the length, then the payload. */
    private fun lengthDelimited(number: Int, payloadSize: Long): ByteArray =
        DelimitedRecords.varint((number.toLong() shl FIELD_NUMBER_SHIFT) or WIRE_LENGTH_DELIMITED.toLong()) +
            DelimitedRecords.varint(payloadSize)

    private const val CLUB_LABEL_FIELD = 2
    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_FIXED32 = 5

    /** A tag is `field_number << FIELD_NUMBER_SHIFT | wire_type`. */
    private const val FIELD_NUMBER_SHIFT = 3
    private const val WIRE_TYPE_MASK = 0x7L
    private const val FIXED64_BYTES = 8
    private const val FIXED32_BYTES = 4
}

/** Parse a framed record as a shot, or null when it is not one. */
internal fun ByteArray.toStoredShot(): R10Protos.StoredShot? =
    DelimitedRecords.body(this)?.let { runCatching { R10Protos.StoredShot.parseFrom(it) }.getOrNull() }

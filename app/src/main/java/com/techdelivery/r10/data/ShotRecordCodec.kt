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

    /** The `shot_id` a record claims, or null when it is not a shot record. */
    fun shotIdOf(framed: ByteArray): Int? = framed.toStoredShot()?.takeIf { it.hasMetrics() }?.metrics?.shotId
}

/** Parse a framed record as a shot, or null when it is not one. */
internal fun ByteArray.toStoredShot(): R10Protos.StoredShot? =
    DelimitedRecords.body(this)?.let { runCatching { R10Protos.StoredShot.parseFrom(it) }.getOrNull() }

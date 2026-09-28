package com.techdelivery.r10.data

import com.google.protobuf.MessageLite

/**
 * Protobuf's length-delimited record framing (ROADMAP R7).
 *
 * Each record on disk is a varint byte-count followed by the encoded message — the
 * same framing `parseDelimitedFrom` reads, so any protobuf tool can open the file.
 * Kept apart from the store because it is pure codec with no knowledge of shots.
 *
 * Why not one message with a `repeated` field (also pure proto): appending would
 * have to rewrite the whole file, one bad byte would make everything after it
 * unreadable, and a torn tail could not be told apart from general corruption. With
 * framing, damage is local to a record — which is the property the store relies on
 * when it reports a truncated tail and still loads everything before it.
 */
internal object DelimitedRecords {

    private const val BITS_PER_PAYLOAD_BYTE = 7
    private const val CONTINUATION_BIT = 0x80
    private const val PAYLOAD_MASK = 0x7F
    private const val MAX_VARINT_BYTES = 10

    /** Frame [message] for storage. */
    fun encode(message: MessageLite): ByteArray {
        val body = message.toByteArray()
        val prefix = ByteArray(varintSize(body.size))
        writeVarint(prefix, body.size.toLong())
        return prefix + body
    }

    /**
     * Every framed record in [bytes], prefix included, so a rewrite can write them
     * back verbatim — re-serializing a parsed message would drop fields this build
     * does not know.
     *
     * Reading stops at the first record that cannot be framed, and [onProblem] says
     * why. Past an unreadable length prefix the offsets are no longer trustworthy,
     * and guessing where the next record begins is how one bad byte turns into a
     * whole file of garbage. Everything before it is returned: a torn tail must not
     * cost the records that came before it.
     *
     * A length is compared against the bytes that are actually left **before** it is
     * narrowed to an `Int`. A corrupt prefix can claim any value up to 2^64, and
     * `toInt()` on one of those wraps negative — which would sail past a bounds
     * check and throw out of `copyOfRange` instead of being reported as damage.
     */
    fun read(bytes: ByteArray, onProblem: (index: Int, message: String) -> Unit = { _, _ -> }): List<ByteArray> {
        val records = ArrayList<ByteArray>()
        var pos = 0
        var index = 0
        while (pos < bytes.size) {
            val length = readVarint(bytes, pos)
            if (length == null || length.value <= 0) {
                onProblem(index, "unreadable length prefix at byte $pos (${bytes.size - pos} bytes left)")
                return records
            }
            val start = pos + length.size
            val available = (bytes.size - start).toLong()
            if (length.value > available) {
                onProblem(
                    index,
                    "truncated record at byte $pos: needs ${length.value} bytes, " +
                        "$available available",
                )
                return records
            }
            val end = start + length.value.toInt()
            records.add(bytes.copyOfRange(pos, end))
            pos = end
            index++
        }
        return records
    }

    /**
     * The message body of a framed record, or null if the frame is malformed.
     *
     * Malformed includes a length the remaining bytes cannot satisfy — see [read]
     * for why the comparison happens before the narrowing to `Int`.
     */
    fun body(framed: ByteArray): ByteArray? {
        val length = readVarint(framed, 0) ?: return null
        if (length.value <= 0) return null
        val start = length.size
        if (length.value > (framed.size - start).toLong()) return null
        return framed.copyOfRange(start, start + length.value.toInt())
    }

    private fun varintSize(value: Int): Int {
        var remaining = value.toLong()
        var size = 1
        while (remaining >= PAYLOAD_MASK) {
            remaining = remaining shr BITS_PER_PAYLOAD_BYTE
            size++
        }
        return size
    }

    private fun writeVarint(out: ByteArray, value: Long) {
        var remaining = value
        var i = 0
        while (true) {
            val bits = (remaining and PAYLOAD_MASK.toLong()).toInt()
            remaining = remaining shr BITS_PER_PAYLOAD_BYTE
            if (remaining == 0L) {
                out[i] = bits.toByte()
                return
            }
            out[i] = (bits or CONTINUATION_BIT).toByte()
            i++
        }
    }

    /**
     * Frame raw message [body] bytes for storage, with no message type involved.
     *
     * The counterpart of [read] for the case where the payload must not be
     * re-serialized — see [ShotRecordCodec.withClubLabel], which rewrites one field
     * and copies the rest exactly as they were.
     */
    fun encodeBody(body: ByteArray): ByteArray {
        val prefix = ByteArray(varintSize(body.size))
        writeVarint(prefix, body.size.toLong())
        return prefix + body
    }

    /** A varint, encoded. */
    internal fun varint(value: Long): ByteArray {
        val out = ByteArray(varintSize(value.toInt()))
        writeVarint(out, value)
        return out
    }

    /**
     * Read the varint starting at [offset], or null when there is not a complete one
     * there.
     *
     * Internal rather than private because a caller walking a message's fields needs
     * the same decoding this does — see [ShotRecordCodec.withClubLabel], which must
     * skip a field without understanding it.
     */
    internal fun readVarint(bytes: ByteArray, offset: Int): Varint? = readVarintAt(bytes, offset)

    private fun readVarintAt(bytes: ByteArray, offset: Int): Varint? {
        var result = 0L
        var shift = 0
        var i = offset
        var count = 0
        while (i < bytes.size && count < MAX_VARINT_BYTES) {
            val b = bytes[i].toInt() and 0xFF
            result = result or ((b.toLong() and PAYLOAD_MASK.toLong()) shl shift)
            i++
            count++
            if (b and CONTINUATION_BIT == 0) return Varint(result, i - offset)
            shift += BITS_PER_PAYLOAD_BYTE
        }
        return null
    }

    internal data class Varint(val value: Long, val size: Int)
}

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
            val end = start + length.value.toInt()
            if (end > bytes.size) {
                onProblem(
                    index,
                    "truncated record at byte $pos: needs ${length.value} bytes, " +
                        "${bytes.size - start} available",
                )
                return records
            }
            records.add(bytes.copyOfRange(pos, end))
            pos = end
            index++
        }
        return records
    }

    /** The message body of a framed record, or null if the frame is malformed. */
    fun body(framed: ByteArray): ByteArray? {
        val length = readVarint(framed, 0) ?: return null
        if (length.value <= 0) return null
        val start = length.size
        val end = start + length.value.toInt()
        if (end > framed.size) return null
        return framed.copyOfRange(start, end)
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

    private fun readVarint(bytes: ByteArray, offset: Int): Varint? {
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

    private data class Varint(val value: Long, val size: Int)
}

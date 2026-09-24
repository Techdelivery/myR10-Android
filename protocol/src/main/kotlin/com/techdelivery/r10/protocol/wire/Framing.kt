package com.techdelivery.r10.protocol.wire

import com.techdelivery.r10.protocol.util.ByteUtil
import com.techdelivery.r10.protocol.util.Cobs
import com.techdelivery.r10.protocol.util.Crc16

/**
 * Frame construction / parsing per DESIGN §5.2 and §5.5.
 *
 * frame(msg) = LE16(len) || msg || CRC16(LE16(len) || msg),  len = 2 + len(msg) + 2
 * wire stream S = 0x00 || COBS(frame) || 0x00
 *
 * Header-byte prefixing is NOT done here — the engine prepends the current header
 * to each slice at write time (§5.2 step 6, §5.8).
 */
object Framing {

    fun frame(msg: ByteArray): ByteArray {
        val len = 2 + msg.size + 2
        val lenBytes = ByteUtil.u16le(len)
        val head = ByteUtil.concat(lenBytes, msg)
        return ByteUtil.concat(head, Crc16.computeLe(head))
    }

    /** COBS-wrap + 0x00 delimiters, then slice into <= CHUNK_SIZE pieces (header-free). */
    fun sliceToChunks(msg: ByteArray): List<ByteArray> {
        val encoded = ByteUtil.concat(byteArrayOf(0x00), Cobs.encode(frame(msg)), byteArrayOf(0x00))
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i < encoded.size) {
            val end = minOf(i + WireConstants.CHUNK_SIZE, encoded.size)
            out.add(encoded.copyOfRange(i, end))
            i = end
        }
        return out
    }

    /** Parse + CRC-verify a decoded frame -> msg. Null on CRC mismatch or too-short frame. */
    fun unframe(frame: ByteArray): ByteArray? {
        if (frame.size < 4) return null
        val expected = ByteUtil.readU16le(frame, 0)
        if (expected != frame.size) return null
        val head = frame.copyOfRange(0, frame.size - 2)
        val computed = Crc16.compute(head)
        val stored = ByteUtil.readU16le(frame, frame.size - 2) // CRC bytes are LE
        if (computed != stored) return null
        return frame.copyOfRange(2, frame.size - 2)
    }
}

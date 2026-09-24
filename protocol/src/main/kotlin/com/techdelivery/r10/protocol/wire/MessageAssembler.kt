package com.techdelivery.r10.protocol.wire

import com.techdelivery.r10.protocol.util.Cobs

/**
 * Receive-side reassembly, ported from the reference ReaderThread (DESIGN §5.3).
 *
 * Each incoming chunk: first byte is the header — strip it.
 *   - If header == 0 OR handshake not yet complete -> route body to handshake.
 *   - Else: trailing 0x00 marks completion; a leading 0x00 (after trailing-strip)
 *     starts a new message (clears the accumulator). Accumulate, then on complete
 *     COBS-decode and emit the frame.
 *
 * Empty COBS decode (malformed) is dropped, never thrown (§5.1).
 */
class MessageAssembler(
    val onHandshakeBody: suspend (ByteArray) -> Unit,
    val onFrame: suspend (ByteArray) -> Unit,
) {
    @Volatile
    var handshakeComplete: Boolean = false

    private val acc = ArrayList<Byte>()

    suspend fun onChunk(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        val body = chunk.copyOfRange(1, chunk.size) // strip header
        if (chunk[0] == 0.toByte() || !handshakeComplete) {
            onHandshakeBody(body)
            return
        }
        var complete = false
        var b = body
        if (b.isNotEmpty() && b[b.size - 1] == 0x00.toByte()) {
            complete = true
            b = b.copyOfRange(0, b.size - 1)
        }
        if (b.isNotEmpty() && b[0] == 0x00.toByte()) {
            acc.clear()
            b = b.copyOfRange(1, b.size)
        }
        acc.addAll(b.toList())
        if (complete && acc.isNotEmpty()) {
            val decoded = Cobs.decode(acc.toByteArray())
            acc.clear()
            if (decoded.isNotEmpty()) onFrame(decoded) // empty decode = drop
        }
    }

    fun reset() = acc.clear()
}

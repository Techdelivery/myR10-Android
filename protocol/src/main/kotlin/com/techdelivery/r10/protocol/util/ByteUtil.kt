package com.techdelivery.r10.protocol.util

/**
 * Little-endian byte helpers. All integers on the R10 wire are little-endian
 * (DESIGN §5.1). Hex helpers are case-insensitive on input, lowercase on output.
 */
object ByteUtil {

    fun u16le(value: Int): ByteArray {
        require(value in 0..0xFFFF) { "u16 out of range: $value" }
        return byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
    }

    fun u32le(value: Long): ByteArray {
        require(value in 0..0xFFFFFFFFL) { "u32 out of range: $value" }
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte(),
        )
    }

    fun readU16le(bytes: ByteArray, offset: Int = 0): Int {
        require(offset >= 0 && offset + 2 <= bytes.size) { "readU16le OOB: offset=$offset size=${bytes.size}" }
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    fun readU32le(bytes: ByteArray, offset: Int = 0): Long {
        require(offset >= 0 && offset + 4 <= bytes.size) { "readU32le OOB: offset=$offset size=${bytes.size}" }
        return (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }

    fun toHex(bytes: ByteArray, sep: String = ""): String =
        bytes.joinToString(sep) { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    /** Accepts any mix of case and ignores spaces / colons / dashes. */
    fun fromHex(hex: String): ByteArray {
        val clean = buildString {
            for (c in hex) if (c.isDigit() || (c in 'a'..'f') || (c in 'A'..'F')) append(c)
        }
        require(clean.length % 2 == 0) { "hex length must be even, got '${clean.length}'" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun concat(vararg arrays: ByteArray): ByteArray {
        val out = ByteArray(arrays.sumOf { it.size })
        var pos = 0
        for (a in arrays) {
            a.copyInto(out, pos)
            pos += a.size
        }
        return out
    }
}

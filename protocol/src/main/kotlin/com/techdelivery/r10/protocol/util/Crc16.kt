package com.techdelivery.r10.protocol.util

/**
 * CRC-16/ARC: reflected polynomial 0xA001, init 0x0000, table-driven, result
 * emitted little-endian (low byte first). DESIGN §5.1.
 *
 * Frozen check vector: ASCII "123456789" -> 0xBB3D -> LE bytes [3D BB].
 * (Catalogue value, cross-verified; NOT 0xBEEF and NOT crc_hqx/XMODEM.)
 */
object Crc16 {
    private const val POLY = 0xA001

    private val table: IntArray = IntArray(256).also { t ->
        for (i in 0..255) {
            var v = i
            repeat(8) { v = if (v and 1 != 0) (v ushr 1) xor POLY else v ushr 1 }
            t[i] = v and 0xFFFF
        }
    }

    /** CRC over bytes[offset until offset+length]. Returns 16-bit value 0..0xFFFF. */
    fun compute(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Int {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        var crc = 0
        for (i in offset until offset + length) {
            crc = (crc ushr 8) xor table[(crc xor bytes[i].toInt()) and 0xFF]
        }
        return crc and 0xFFFF
    }

    /** CRC as the 2 little-endian bytes appended to a frame (DESIGN §5.2 step 3). */
    fun computeLe(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): ByteArray {
        val crc = compute(bytes, offset, length)
        return byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte())
    }
}

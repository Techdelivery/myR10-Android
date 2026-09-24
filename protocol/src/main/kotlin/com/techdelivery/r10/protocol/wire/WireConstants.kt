package com.techdelivery.r10.protocol.wire

import com.techdelivery.r10.protocol.util.ByteUtil

/**
 * Every magic byte on the R10 wire, each tagged with its DESIGN.md §.
 * These are field-verified — never "clean them up" (DESIGN §5 preamble).
 */
object WireConstants {

    /** §5.4 step 1 — first raw write body (12 bytes, header prepended by transport -> 13 on air). */
    val HANDSHAKE_FIRST_WRITE: ByteArray = ByteUtil.fromHex("000000000000000000010000")

    /** §5.4 step 2 — device reply prefix (matched against the header-stripped body). */
    val HANDSHAKE_REPLY_PREFIX: ByteArray = ByteUtil.fromHex("010000000000000000010000")

    /** §5.4 step 3 — index of the dynamic header byte within the stripped reply body. */
    const val HANDSHAKE_HEADER_INDEX: Int = 12

    /** §5.4 step 4 — final raw handshake write body (single 0x00; header prepended -> [H,00]). */
    val HANDSHAKE_FINAL_WRITE: ByteArray = byteArrayOf(0x00)

    /** §5.5 — message types are two RAW bytes (not ASCII). */
    val TYPE_A0: ByteArray = byteArrayOf(0xA0.toByte(), 0x13) // device info
    val TYPE_BA: ByteArray = byteArrayOf(0xBA.toByte(), 0x13) // config
    val TYPE_B4: ByteArray = byteArrayOf(0xB4.toByte(), 0x13) // protobuf response
    val TYPE_B3: ByteArray = byteArrayOf(0xB3.toByte(), 0x13) // protobuf request (device->app)
    val TYPE_ACK: ByteArray = byteArrayOf(0x88.toByte(), 0x13) // app ack

    /** §5.2 step 6 — max payload bytes per GATT write slice (header added at write time -> <=20). */
    const val CHUNK_SIZE: Int = 19

    /** §5.5 — inbound proto payload always starts at msg offset 16. */
    const val INBOUND_PROTO_OFFSET: Int = 16

    /** §5.6 — B4/B3 ack appends LE16(counter) + 14 zero bytes after the base body. */
    val ACK_COUNTER_TAIL: ByteArray = ByteArray(14)

    /** §5.4 step 5 — handshake abort timeout. */
    const val HANDSHAKE_TIMEOUT_MS: Long = 10_000L

    /** §5.7 — request/response timeout. */
    const val REQUEST_TIMEOUT_MS: Long = 5_000L

    fun isType(msg: ByteArray, type: ByteArray): Boolean =
        msg.size >= 2 && msg[0] == type[0] && msg[1] == type[1]
}

package com.techdelivery.r10.protocol.wire

import LaunchMonitor.Proto.R10Protos
import com.techdelivery.r10.protocol.util.ByteUtil

/**
 * Ack construction + type routing for decoded frames (DESIGN §5.5, §5.6).
 *
 * Every recognized-or-unknown frame yields an ack to send (except a CRC-mismatch
 * drop). The ack payload is carried on each non-drop Dispatch so the engine
 * always writes it through the framing path with the current header.
 *
 * CRC mismatch -> log + drop (deliberate hardening; the reference logs and
 * continues, we drop to avoid dispatching/acking garbage).
 */
class FrameDispatcher(private val log: (String) -> Unit = {}) {

    sealed interface Dispatch {
        /** Ack payload P to send through the framing path with the current header. */
        val ackPayload: ByteArray?

        /** B413 response whose counter matches the pending request. */
        data class Response(val counter: Int, val proto: R10Protos.WrapperProto, override val ackPayload: ByteArray) :
            Dispatch

        /** B313 device-originated request (e.g. event notification). */
        data class DeviceRequest(val proto: R10Protos.WrapperProto, override val ackPayload: ByteArray) : Dispatch

        /** A013 device info / BA13 config — acked, not parsed. */
        data class InfoAck(val type: String, override val ackPayload: ByteArray) : Dispatch

        /** Unrecognized type — base ack only. */
        data class UnknownAck(val typeHex: String, override val ackPayload: ByteArray) : Dispatch

        /** CRC/length mismatch — dropped, no ack. */
        data object Dropped : Dispatch {
            override val ackPayload: ByteArray? get() = null
        }
    }

    /** §5.6 — build the ack payload P for a received msg. */
    fun buildAck(msg: ByteArray): ByteArray {
        val origType = msg.copyOfRange(0, 2)
        return if (WireConstants.isType(msg, WireConstants.TYPE_B4) ||
            WireConstants.isType(msg, WireConstants.TYPE_B3)
        ) {
            val counter = ByteUtil.readU16le(msg, 2)
            ByteUtil.concat(
                WireConstants.TYPE_ACK,
                origType,
                byteArrayOf(0x00),
                ByteUtil.u16le(counter),
                WireConstants.ACK_COUNTER_TAIL,
            )
        } else {
            ByteUtil.concat(WireConstants.TYPE_ACK, origType, byteArrayOf(0x00))
        }
    }

    /** Process a decoded frame -> dispatch decision (always carries the ack, except Dropped). */
    fun dispatch(frame: ByteArray, expectedCounter: Int): Dispatch {
        val msg = Framing.unframe(frame)
        if (msg == null) {
            log("CRC/length mismatch — dropping frame")
            return Dispatch.Dropped
        }
        val ack = buildAck(msg)
        return when {
            WireConstants.isType(msg, WireConstants.TYPE_A0) -> Dispatch.InfoAck("A013", ack)

            WireConstants.isType(msg, WireConstants.TYPE_BA) -> Dispatch.InfoAck("BA13", ack)

            WireConstants.isType(msg, WireConstants.TYPE_B4) -> {
                val counter = ByteUtil.readU16le(msg, 2)
                if (counter == expectedCounter) {
                    val proto = R10Protos.WrapperProto.parseFrom(
                        msg.copyOfRange(WireConstants.INBOUND_PROTO_OFFSET, msg.size),
                    )
                    Dispatch.Response(counter, proto, ack)
                } else {
                    Dispatch.InfoAck("B413-stale", ack) // ack only, no completion
                }
            }

            WireConstants.isType(msg, WireConstants.TYPE_B3) -> {
                val proto = R10Protos.WrapperProto.parseFrom(
                    msg.copyOfRange(WireConstants.INBOUND_PROTO_OFFSET, msg.size),
                )
                Dispatch.DeviceRequest(proto, ack)
            }

            else -> Dispatch.UnknownAck(ByteUtil.toHex(msg.copyOfRange(0, minOf(2, msg.size))), ack)
        }
    }
}

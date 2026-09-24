package com.techdelivery.r10.protocol.wire

/**
 * Handshake state machine (DESIGN §5.4). Pure function of the header-stripped
 * reply bodies; the engine owns the actual writes and the ~10s timeout.
 *
 * onBody(body): if body starts with the reply prefix, extract the dynamic header
 * at index 12 and return the final raw write payload [H, 0x00]. Null otherwise.
 *
 * Field traffic shows the reply arrives as one chunk; the reference matches each
 * stripped body independently, so we do the same. (If a device ever splits the
 * reply across chunks, extend to accumulate pre-handshake bodies — see DESIGN
 * §5.4 step 2 note; the H2 golden capture will reveal if needed.)
 */
class HandshakeStateMachine {

    enum class State { IDLE, WAITING_REPLY, DONE }

    var state: State = State.IDLE
        private set

    var dynamicHeader: Int = -1
        private set

    fun begin() {
        state = State.WAITING_REPLY
    }

    fun onBody(body: ByteArray): ByteArray? {
        if (state != State.WAITING_REPLY) return null
        if (body.size < WireConstants.HANDSHAKE_HEADER_INDEX + 1) return null
        for (i in WireConstants.HANDSHAKE_REPLY_PREFIX.indices) {
            if (body[i] != WireConstants.HANDSHAKE_REPLY_PREFIX[i]) return null
        }
        dynamicHeader = body[WireConstants.HANDSHAKE_HEADER_INDEX].toInt() and 0xFF
        state = State.DONE
        // Final handshake write BODY (0x00); the engine prepends the header -> [H, 0x00] on air.
        return byteArrayOf(0x00)
    }

    val isComplete: Boolean get() = state == State.DONE
}

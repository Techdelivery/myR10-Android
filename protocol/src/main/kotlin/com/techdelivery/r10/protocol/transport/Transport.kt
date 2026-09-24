package com.techdelivery.r10.protocol.transport

import kotlinx.coroutines.flow.Flow
import java.util.UUID

enum class TransportState {
    DISCONNECTED,
    CONNECTING,
    SCANNING,
    CONNECTED,
    DISCONNECTING,
}

/**
 * Abstraction over the BLE GATT link. The engine prepends the current header
 * byte before every write, so [write] receives the full on-air chunk.
 *
 * [incoming] emits one raw GATT notification chunk per emission, header byte
 * intact — stripping/reassembly is the engine's job (DESIGN §5.3).
 */
interface Transport {
    val incoming: Flow<ByteArray>
    val state: Flow<TransportState>

    /** One GATT write-with-response; [chunk] already carries its header byte. */
    suspend fun write(chunk: ByteArray)

    /** Enable notifications/indications on a characteristic (write CCCD 0x0001). */
    suspend fun subscribe(uuid: UUID)

    /** Read a characteristic once and return its raw bytes. */
    suspend fun read(uuid: UUID): ByteArray

    /** Bring the link up (scan/connect/discover). */
    suspend fun start()

    /** Tear the link down. */
    suspend fun stop()
}

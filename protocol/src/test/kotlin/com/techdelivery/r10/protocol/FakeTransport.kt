package com.techdelivery.r10.protocol

import com.techdelivery.r10.protocol.transport.Transport
import com.techdelivery.r10.protocol.transport.TransportState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory Transport for protocol tests. Captures on-air write chunks and lets
 * the test push inbound notification chunks.
 */
class FakeTransport : Transport {
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    override val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

    private val _state = MutableStateFlow(TransportState.CONNECTED)
    override val state: Flow<TransportState> = _state.asStateFlow()

    /** Every on-air chunk the engine wrote, in order. */
    val writes = mutableListOf<ByteArray>()

    override suspend fun write(chunk: ByteArray) {
        writes.add(chunk)
    }

    override suspend fun start() { _state.value = TransportState.CONNECTED }
    override suspend fun stop() { _state.value = TransportState.DISCONNECTED }

    /** Simulate a device GATT notification chunk (header byte intact). */
    suspend fun emit(chunk: ByteArray) {
        _incoming.emit(chunk)
    }
}

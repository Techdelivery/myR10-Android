package com.techdelivery.r10.protocol

import LaunchMonitor.Proto.R10Protos
import com.techdelivery.r10.protocol.transport.Transport
import com.techdelivery.r10.protocol.util.ByteUtil
import com.techdelivery.r10.protocol.util.HexLog
import com.techdelivery.r10.protocol.wire.Framing
import com.techdelivery.r10.protocol.wire.FrameDispatcher
import com.techdelivery.r10.protocol.wire.HandshakeStateMachine
import com.techdelivery.r10.protocol.wire.MessageAssembler
import com.techdelivery.r10.protocol.wire.WireConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class ResponseEvent(val counter: Int, val proto: R10Protos.WrapperProto)

/**
 * Wires the wire layer (framing, assembler, handshake SM, dispatcher) into a
 * live state machine over a [Transport] (DESIGN §5.4-§5.7).
 *
 * Owns: the current header byte, the request counter, the single in-flight
 * request. All TX/RX is mirrored to [hexLog].
 */
class ProtocolEngine(
    private val transport: Transport,
    val hexLog: HexLog = HexLog(),
    private val scope: CoroutineScope,
) {
    @Volatile
    private var header: Int = 0x00

    @Volatile
    var currentRequestCounter: Int = 0
        private set

    private val handshake = HandshakeStateMachine()
    private val dispatcher = FrameDispatcher { /* dispatch notes; not byte-level, not logged to hexLog */ }

    private val assembler = MessageAssembler(
        onHandshakeBody = { body -> handleHandshakeBody(body) },
        onFrame = { frame -> handleFrame(frame) },
    )

    private val _handshakeComplete = MutableSharedFlow<Unit>(replay = 1)
    val handshakeComplete: Flow<Unit> = _handshakeComplete.asSharedFlow()

    private val _events = MutableSharedFlow<R10Protos.WrapperProto>(extraBufferCapacity = 64)
    val eventNotification: Flow<R10Protos.WrapperProto> = _events.asSharedFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val error: Flow<String> = _errors.asSharedFlow()

    private var pending: kotlinx.coroutines.CompletableDeferred<ResponseEvent>? = null
    private val sendMutex = Mutex()
    private var collectorJob: Job? = null

    val isHandshakeComplete: Boolean get() = handshake.isComplete

    fun start() {
        handshake.begin()
        assembler.handshakeComplete = false
        collectorJob = scope.launch {
            transport.incoming.collect { chunk ->
                hexLog.rx(chunk)
                assembler.onChunk(chunk)
            }
        }
        scope.launch { writeRaw(WireConstants.HANDSHAKE_FIRST_WRITE) }
    }

    fun stop() {
        collectorJob?.cancel()
        collectorJob = null
        pending?.cancel()
        pending = null
    }

    private suspend fun handleHandshakeBody(body: ByteArray) {
        val finalBody = handshake.onBody(body)
        if (finalBody != null) {
            header = handshake.dynamicHeader
            assembler.handshakeComplete = true
            writeRaw(finalBody) // -> [H, 0x00] on air
            _handshakeComplete.emit(Unit)
        }
    }

    private suspend fun handleFrame(frame: ByteArray) {
        val d = dispatcher.dispatch(frame, currentRequestCounter)
        d.ackPayload?.let { sendFramed(it) }
        when (d) {
            is FrameDispatcher.Dispatch.Response -> {
                if (d.counter == currentRequestCounter) {
                    pending?.complete(ResponseEvent(d.counter, d.proto))
                    pending = null
                    currentRequestCounter++
                }
            }
            is FrameDispatcher.Dispatch.DeviceRequest -> _events.emit(d.proto)
            else -> Unit
        }
    }

    /**
     * Send a protobuf request (§5.7) and await its matching B4 response.
     * One request in flight; counter increments only on success.
     * Returns null on timeout (counter left unchanged).
     */
    suspend fun sendProtobufRequest(proto: R10Protos.WrapperProto): ResponseEvent? {
        return sendMutex.withLock {
            val counter = currentRequestCounter
            val payload = buildRequestPayload(counter, proto.toByteArray())
            val deferred = CompletableDeferred<ResponseEvent>()
            pending = deferred
            sendFramed(payload)
            val result = withTimeoutOrNull(WireConstants.REQUEST_TIMEOUT_MS) { deferred.await() }
            if (result == null) pending = null
            result
        }
    }

    private fun buildRequestPayload(counter: Int, protoBytes: ByteArray): ByteArray {
        val l = protoBytes.size
        return ByteUtil.concat(
            WireConstants.TYPE_B3,
            // Counter is a C# `int` in the reference, so BitConverter.GetBytes(int) emits
            // FOUR bytes little-endian (BaseDevice.cs:280). DESIGN §5.7 claimed LE16 and
            // "proto starts 14 bytes into P"; that is wrong — the device needs the proto at
            // offset 16, the same as inbound frames (§5.5). With a 2-byte counter the proto
            // lands at 14 and the device parses garbage: it acks the frame but never sends
            // a B413 response. Hardware-verified 2026-09-24.
            ByteUtil.u32le(counter.toLong()),
            byteArrayOf(0x00, 0x00),
            ByteUtil.u32le(l.toLong()),
            ByteUtil.u32le(l.toLong()),
            protoBytes,
        )
    }

    /** Raw write: prepend current header, log TX, hand the full chunk to transport. */
    private suspend fun writeRaw(body: ByteArray) {
        val chunk = byteArrayOf(header.toByte()) + body
        hexLog.tx(chunk)
        transport.write(chunk)
    }

    /** Framed write: slice the msg, prepend header to each slice, write each. */
    private suspend fun sendFramed(msg: ByteArray) {
        for (slice in Framing.sliceToChunks(msg)) {
            val chunk = byteArrayOf(header.toByte()) + slice
            hexLog.tx(chunk)
            transport.write(chunk)
        }
    }
}

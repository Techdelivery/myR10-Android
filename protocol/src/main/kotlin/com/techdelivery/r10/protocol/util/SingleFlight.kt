package com.techdelivery.r10.protocol.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes a sequence of operations so only one runs at a time. BLE GATT
 * permits a single outstanding operation, so every write/read/subscribe is
 * funnelled through one of these to guarantee no overlap (DESIGN §5.2 writes,
 * §7.1 serialized single-flight writes-with-response).
 */
class SingleFlight {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}

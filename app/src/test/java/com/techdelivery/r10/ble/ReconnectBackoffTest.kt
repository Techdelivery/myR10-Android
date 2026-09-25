package com.techdelivery.r10.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * M3 — reconnect backoff shape (DESIGN §11: "retry with backoff").
 */
class ReconnectBackoffTest {

    @Test
    fun doublesFromBaseEachAttempt() {
        assertEquals(5_000L, BleTransportImpl.backoffDelayMs(5_000L, 1))
        assertEquals(10_000L, BleTransportImpl.backoffDelayMs(5_000L, 2))
        assertEquals(20_000L, BleTransportImpl.backoffDelayMs(5_000L, 3))
        assertEquals(40_000L, BleTransportImpl.backoffDelayMs(5_000L, 4))
    }

    @Test
    fun isCapped() {
        assertEquals(60_000L, BleTransportImpl.backoffDelayMs(5_000L, 5))
        assertEquals(60_000L, BleTransportImpl.backoffDelayMs(5_000L, 50))
        assertEquals(15_000L, BleTransportImpl.backoffDelayMs(4_000L, 3, capMs = 15_000L))
    }

    @Test
    fun nonPositiveBaseCollapsesToCapInsteadOfHotLooping() {
        assertEquals(60_000L, BleTransportImpl.backoffDelayMs(0L, 1))
        assertEquals(30_000L, BleTransportImpl.backoffDelayMs(-7L, 4, capMs = 30_000L))
    }

    @Test
    fun neverReturnsZero() {
        assertEquals(1L, BleTransportImpl.backoffDelayMs(1L, 1, capMs = 1L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun attemptIsOneBased() {
        BleTransportImpl.backoffDelayMs(5_000L, 0)
    }
}

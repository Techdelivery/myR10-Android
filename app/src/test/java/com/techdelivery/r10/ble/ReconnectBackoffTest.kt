package com.techdelivery.r10.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /**
     * W8 — the old `baseMs shl n` wrapped negative once the product passed 2^63,
     * and the trailing `coerceAtLeast(1)` turned that wrap into a 1 ms hot retry:
     * the exact failure this function exists to prevent. Growth is in `Double` now,
     * which saturates instead of wrapping.
     */
    @Test
    fun hugeBaseSaturatesInsteadOfWrappingTo1ms() {
        // 1e13 * 2^20 = 1.05e19 > Long.MAX_VALUE (9.22e18): the old code wrapped
        // negative and returned 1. The cap must win here.
        assertEquals(
            Long.MAX_VALUE,
            BleTransportImpl.backoffDelayMs(10_000_000_000_000L, 21, capMs = Long.MAX_VALUE),
        )
        assertEquals(60_000L, BleTransportImpl.backoffDelayMs(Long.MAX_VALUE, 63))
        // Never below the cap, never below 1 ms, for any attempt count.
        for (attempt in 1..200) {
            val d = BleTransportImpl.backoffDelayMs(10_000_000_000_000L, attempt, capMs = Long.MAX_VALUE)
            assertTrue("attempt $attempt gave $d", d >= 1L)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun capMustBePositive() {
        BleTransportImpl.backoffDelayMs(5_000L, 1, capMs = 0L)
    }
}

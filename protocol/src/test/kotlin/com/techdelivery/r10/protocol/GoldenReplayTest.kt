package com.techdelivery.r10.protocol

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * H1 — golden replay harness (DESIGN §5 / plan M1).
 *
 * Replays a captured real-device session (`golden/session-r10.hex`, one
 * `TX <hex>` / `RX <hex>` event per line) through the engine over a
 * [FakeTransport], asserting the handshake completes and every inbound chunk
 * is handled without error.
 *
 * The golden file is captured during the [HW] H2 hardware session. Until then
 * this test SKIPS cleanly so CI stays green.
 */
class GoldenReplayTest {

    private val goldenResource = "golden/session-r10.hex"

    @Test
    fun replayGoldenSession() {
        val stream = javaClass.classLoader?.getResourceAsStream(goldenResource)
        Assume.assumeTrue(
            "$goldenResource absent — captured during [HW] H2; skipping replay",
            stream != null,
        )

        val lines = stream!!.bufferedReader().readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        val rxChunks = lines.filter { it.startsWith("RX ") }
            .map { hexToBytes(it.removePrefix("RX ")) }
            .filter { it.isNotEmpty() }

        Assume.assumeTrue("golden file has no RX events", rxChunks.isNotEmpty())

        // A golden captured while the device was failing to answer would be worthless:
        // assert it actually contains B413 responses (the H2 fix's signature).
        org.junit.Assert.assertTrue(
            "golden must contain at least one B413 response — a capture with only " +
                "8813 acks means the request layout regressed",
            lines.any { it.startsWith("RX ") && it.contains("B4 13") },
        )

        runTest {
            val fake = FakeTransport()
            val engine = ProtocolEngine(fake, scope = this)
            engine.start()

            // handshakeComplete is a replay-less SharedFlow: subscribe BEFORE feeding
            // any chunks, otherwise a fast handshake is missed entirely.
            val completed = async {
                runCatching { withTimeout(5_000) { engine.handshakeComplete.first() } }
            }
            runCurrent()

            // Feed every captured inbound chunk through the live engine.
            rxChunks.forEach {
                fake.emit(it)
                runCurrent()
            }

            assertTrue(
                "handshake must complete when replaying golden RX",
                completed.await().isSuccess,
            )

            engine.stop()
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "")
        if (clean.isEmpty()) return ByteArray(0)
        require(clean.length % 2 == 0) { "odd hex length: $hex" }
        return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

package com.techdelivery.r10.protocol

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
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

        runBlocking {
            val fake = FakeTransport()
            val engine = ProtocolEngine(fake, scope = this)
            engine.start()

            // Feed every captured inbound chunk through the live engine.
            rxChunks.forEach { fake.emit(it) }

            val completed = withTimeoutOrNull(3_000) { engine.handshakeComplete.first() }
            assertNotNull("handshake must complete when replaying golden RX", completed)

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

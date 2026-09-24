package com.techdelivery.r10.protocol

import com.techdelivery.r10.protocol.util.ByteUtil
import com.techdelivery.r10.protocol.wire.GattUuids
import com.techdelivery.r10.protocol.wire.WireConstants
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G2 — R10Device facade: the §7.1 setup order is driven through the Transport,
 * asserted against a fake that records the call sequence.
 */
class R10DeviceTest {

    private val dynHeader: Byte = 0xAB.toByte()
    private val replyBody = ByteUtil.concat(
        WireConstants.HANDSHAKE_REPLY_PREFIX,
        byteArrayOf(dynHeader),
        byteArrayOf(0x01, 0x02, 0x03),
    )
    private val handshakeFirstWrite = byteArrayOf(0x00) + WireConstants.HANDSHAKE_FIRST_WRITE

    private fun populatedFake(): FakeTransport = FakeTransport().apply {
        readValues[GattUuids.SERIAL_NUMBER] = "SN-0001".toByteArray()
        readValues[GattUuids.FIRMWARE_REV] = "FW-2.3.1".toByteArray()
        readValues[GattUuids.MODEL_NAME] = "R10-PRO".toByteArray()
        readValues[GattUuids.BATTERY_LEVEL] = byteArrayOf(88.toByte())
    }

    @Test
    fun connectRunsGattSetupInDocumentedOrder() = runTest {
        val fake = populatedFake()
        val engine = ProtocolEngine(fake, scope = this)
        val device = R10Device(fake, engine, DeviceSetupConfig())

        var ok = false
        val connectJob = launch { ok = device.connect() }

        // Drive the handshake once the engine emits its first write.
        while (fake.writes.none { it.contentEquals(handshakeFirstWrite) }) yield()
        fake.emit(byteArrayOf(0x00) + replyBody)
        connectJob.join()

        assertTrue("handshake should complete", ok)

        val expected = listOf(
            "start",
            "sub:${GattUuids.MEASUREMENT}",
            "sub:${GattUuids.CONTROL_POINT}",
            "sub:${GattUuids.STATUS}",
            "read:${GattUuids.SERIAL_NUMBER}",
            "read:${GattUuids.FIRMWARE_REV}",
            "read:${GattUuids.MODEL_NAME}",
            "read:${GattUuids.BATTERY_LEVEL}",
            "sub:${GattUuids.BATTERY_LEVEL}",
            "sub:${GattUuids.DATA_NOTIFIER}",
        )
        assertEquals(expected, fake.ops)
        engine.stop()
    }

    @Test
    fun deviceInfoParsedFromReads() = runTest {
        val fake = populatedFake()
        val engine = ProtocolEngine(fake, scope = this)
        val device = R10Device(fake, engine, DeviceSetupConfig())

        val connectJob = launch { device.connect() }
        while (fake.writes.none { it.contentEquals(handshakeFirstWrite) }) yield()
        fake.emit(byteArrayOf(0x00) + replyBody)
        connectJob.join()

        val info = device.deviceInfo.value
        assertEquals("R10-PRO", info.model)
        assertEquals("SN-0001", info.serial)
        assertEquals("FW-2.3.1", info.firmware)
        assertEquals(88, info.batteryLevel)
        engine.stop()
    }

    @Test
    fun connectAbortsWhenHandshakeNeverCompletes() = runTest {
        val fake = populatedFake()
        val engine = ProtocolEngine(fake, scope = this)
        val device = R10Device(fake, engine, DeviceSetupConfig())

        // No handshake reply -> connect() returns false after the handshake timeout.
        val ok = device.connect()
        assertTrue("connect must report handshake failure", !ok)
        // Setup ops still ran up to the data-channel subscribe.
        assertTrue(fake.ops.contains("sub:${GattUuids.DATA_NOTIFIER}"))
        engine.stop()
    }

    @Test
    fun batteryPercentHandlesEmptyAndUnsigned() {
        assertEquals(-1, R10Device.batteryPercent(ByteArray(0)))
        assertEquals(0, R10Device.batteryPercent(byteArrayOf(0)))
        assertEquals(100, R10Device.batteryPercent(byteArrayOf(100)))
        assertEquals(200, R10Device.batteryPercent(byteArrayOf((-56).toByte()))) // 0xC8 unsigned
    }
}

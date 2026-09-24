package com.techdelivery.r10.protocol

import LaunchMonitor.Proto.R10Protos
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * G3 — ShotConfig serialization pin (§7.1 step 11, §8 defaults).
 * Catches accidental proto-schema drift or a changed tee_range formula.
 */
class ShotConfigTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }

    @Test
    fun shotConfigRequestPinsDefaultsHex() {
        val req = R10Device.shotConfigRequest(DeviceSetupConfig())
        // temperature=60, humidity=1, altitude=0, airDensity=1, tee_range=7/3.281f
        // (float division; the exact IEEE-754 bits the device actually receives)
        assertEquals(
            "0D00007042150000803F1D00000000250000803F2D338B0840",
            hex(req.toByteArray()),
        )
        assertEquals(25, req.serializedSize)
    }

    @Test
    fun teeRangeFormulaIsFeetOver3_281() {
        val req = R10Device.shotConfigRequest(DeviceSetupConfig(teeDistanceFt = 7))
        assertEquals(7 / 3.281f, req.teeRange, 1e-6f)

        val ten = R10Device.shotConfigRequest(DeviceSetupConfig(teeDistanceFt = 10))
        assertEquals(10 / 3.281f, ten.teeRange, 1e-6f)
    }

    @Test
    fun customSettingsRoundTripThroughProto() {
        val cfg = DeviceSetupConfig(
            temperatureF = 72.5f,
            humidity = 40f,
            altitudeM = 300f,
            airDensity = 1.16f,
            teeDistanceFt = 9,
        )
        val req = R10Device.shotConfigRequest(cfg)
        val parsed = R10Protos.ShotConfigRequest.parseFrom(req.toByteArray())
        assertEquals(72.5f, parsed.temperature, 1e-4f)
        assertEquals(40f, parsed.humidity, 1e-4f)
        assertEquals(300f, parsed.altitude, 1e-4f)
        assertEquals(1.16f, parsed.airDensity, 1e-4f)
        assertEquals(9 / 3.281f, parsed.teeRange, 1e-5f)
    }
}

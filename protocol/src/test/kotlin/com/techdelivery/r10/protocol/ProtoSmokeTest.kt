package com.techdelivery.r10.protocol

import LaunchMonitor.Proto.R10Protos
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B3 smoke test: construct + serialize + re-parse the three proto types the
 * M1 setup sequence depends on, plus the field-1001 quirk. Proves codegen is
 * wired correctly and the lite runtime round-trips bytes faithfully.
 */
class ProtoSmokeTest {

    @Test
    fun shotConfigRequest_roundTrips() {
        val req = R10Protos.ShotConfigRequest.newBuilder()
            .setTemperature(60f)
            .setHumidity(1f)
            .setAltitude(0f)
            .setAirDensity(1f)
            .setTeeRange(7f / 3.281f) // §8: teeDistanceFt 7 -> meters
            .build()
        val bytes = req.toByteArray()
        val parsed = R10Protos.ShotConfigRequest.parseFrom(bytes)
        assertEquals(req, parsed)
        assertArrayEquals(bytes, parsed.toByteArray())
        assertEquals(2.1335f, parsed.getTeeRange(), 1e-3f)
    }

    @Test
    fun alertDetails_roundTrips() {
        val details = R10Protos.AlertDetails.newBuilder()
            .setState(
                R10Protos.State.newBuilder().setState(R10Protos.State.StateType.WAITING),
            )
            .setError(
                R10Protos.Error.newBuilder()
                    .setCode(R10Protos.Error.ErrorCode.PLATFORM_TILTED)
                    .setSeverity(R10Protos.Error.Severity.SERIOUS),
            )
            .build()
        val bytes = details.toByteArray()
        val parsed = R10Protos.AlertDetails.parseFrom(bytes)
        assertEquals(details, parsed)
        assertEquals(R10Protos.State.StateType.WAITING, parsed.getState().getState())
        assertEquals(R10Protos.Error.ErrorCode.PLATFORM_TILTED, parsed.getError().getCode())
        assertEquals(R10Protos.Error.Severity.SERIOUS, parsed.getError().getSeverity())
    }

    @Test
    fun wrapperProto_wrapsService_roundTrips() {
        val shotReq = R10Protos.ShotConfigRequest.newBuilder().setTemperature(72f).build()
        val service = R10Protos.LaunchMonitorService.newBuilder()
            .setShotConfigRequest(shotReq)
            .build()
        val wrapper = R10Protos.WrapperProto.newBuilder()
            .setService(service)
            .build()
        val bytes = wrapper.toByteArray()
        val parsed = R10Protos.WrapperProto.parseFrom(bytes)
        assertEquals(wrapper, parsed)
        assertEquals(72f, parsed.getService().getShotConfigRequest().getTemperature(), 0f)
    }

    @Test
    fun alertNotification_field1001_roundTrips() {
        val inner = R10Protos.AlertDetails.newBuilder()
            .setState(
                R10Protos.State.newBuilder().setState(R10Protos.State.StateType.RECORDING),
            )
            .build()
        val note = R10Protos.AlertNotification.newBuilder()
            .setType(R10Protos.AlertNotification.AlertType.LAUNCH_MONITOR)
            .setAlertNotification(inner) // field 1001 (backing field alertNotification_)
            .build()
        val bytes = note.toByteArray()
        val parsed = R10Protos.AlertNotification.parseFrom(bytes)
        assertEquals(note, parsed)
        assertEquals(
            R10Protos.State.StateType.RECORDING,
            parsed.getAlertNotification().getState().getState(),
        )
    }
}

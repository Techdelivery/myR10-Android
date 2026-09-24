package com.techdelivery.r10.protocol

import LaunchMonitor.Proto.R10Protos
import com.techdelivery.r10.protocol.transport.Transport
import com.techdelivery.r10.protocol.wire.GattUuids
import com.techdelivery.r10.protocol.wire.WireConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Settings the setup sequence needs (subset of AppSettings relevant to the device). */
data class DeviceSetupConfig(
    val temperatureF: Float = 60f,
    val humidity: Float = 1f,
    val altitudeM: Float = 0f,
    val airDensity: Float = 1f,
    val teeDistanceFt: Int = 7,
    val calibrateTiltOnConnect: Boolean = false,
)

data class DeviceInfo(
    val model: String = "",
    val serial: String = "",
    val firmware: String = "",
    val batteryLevel: Int = -1,
)

/**
 * Device facade (DESIGN §3 / §7.1). Owns the [Transport] + [ProtocolEngine] and
 * runs the documented setup order, then exposes the command/request API.
 *
 * Pure JVM: the GATT specifics live behind [Transport], so the whole sequence is
 * testable with a fake transport that records the call order.
 */
class R10Device(
    private val transport: Transport,
    private val engine: ProtocolEngine,
    private val config: DeviceSetupConfig = DeviceSetupConfig(),
) {
    private val _deviceInfo = MutableStateFlow(DeviceInfo())
    val deviceInfo: StateFlow<DeviceInfo> = _deviceInfo.asStateFlow()

    /**
     * Steps 1-5: connect, subscribe measurement/control/status, read device info
     * + battery, subscribe the data-channel notifier, then handshake.
     * Returns true if the handshake completed within [WireConstants.HANDSHAKE_TIMEOUT_MS].
     */
    suspend fun connect(): Boolean {
        transport.start()

        // Step 2 — these come FIRST, before any reads or the handshake.
        transport.subscribe(GattUuids.MEASUREMENT)
        transport.subscribe(GattUuids.CONTROL_POINT)
        transport.subscribe(GattUuids.STATUS)

        // Step 3 — device info as ASCII (serial, firmware, model per §7.1); battery one read then subscribe.
        val serial = transport.read(GattUuids.SERIAL_NUMBER).ascii()
        val firmware = transport.read(GattUuids.FIRMWARE_REV).ascii()
        val model = transport.read(GattUuids.MODEL_NAME).ascii()
        val battery = batteryPercent(transport.read(GattUuids.BATTERY_LEVEL))
        _deviceInfo.value = DeviceInfo(model, serial, firmware, battery)
        transport.subscribe(GattUuids.BATTERY_LEVEL)

        // Step 4 — open the data channel.
        transport.subscribe(GattUuids.DATA_NOTIFIER)

        // Step 5 — handshake.
        engine.start()
        return withTimeoutOrNull(WireConstants.HANDSHAKE_TIMEOUT_MS) {
            engine.handshakeComplete.first()
        } != null
    }

    /** Full §7.1 setup: steps 1-11. Returns false if the handshake aborted. */
    suspend fun runSetup(): Boolean {
        if (!connect()) return false
        wakeUp()                 // 6
        statusRequest()          // 7
        tiltRequest()            // 8
        subscribeAlerts()        // 9
        if (config.calibrateTiltOnConnect) startTiltCalibration() // 10
        sendShotConfig()         // 11
        return true
    }

    // --- Command / request API (steps 6-11) ---

    suspend fun wakeUp(): ResponseEvent? = engine.sendProtobufRequest(wakeUpProto())
    suspend fun statusRequest(): ResponseEvent? = engine.sendProtobufRequest(statusProto())
    suspend fun tiltRequest(): ResponseEvent? = engine.sendProtobufRequest(tiltProto())
    suspend fun subscribeAlerts(): ResponseEvent? = engine.sendProtobufRequest(subscribeAlertsProto())
    suspend fun startTiltCalibration(): ResponseEvent? = engine.sendProtobufRequest(startTiltCalProto())
    suspend fun sendShotConfig(): ResponseEvent? = engine.sendProtobufRequest(shotConfigProto())

    // --- Proto builders ---

    private fun wakeUpProto(): R10Protos.WrapperProto =
        R10Protos.WrapperProto.newBuilder()
            .setService(R10Protos.LaunchMonitorService.newBuilder().setWakeUpRequest(R10Protos.WakeUpRequest.getDefaultInstance()))
            .build()

    private fun statusProto(): R10Protos.WrapperProto =
        R10Protos.WrapperProto.newBuilder()
            .setService(R10Protos.LaunchMonitorService.newBuilder().setStatusRequest(R10Protos.StatusRequest.getDefaultInstance()))
            .build()

    private fun tiltProto(): R10Protos.WrapperProto =
        R10Protos.WrapperProto.newBuilder()
            .setService(R10Protos.LaunchMonitorService.newBuilder().setTiltRequest(R10Protos.TiltRequest.getDefaultInstance()))
            .build()

    private fun startTiltCalProto(): R10Protos.WrapperProto =
        R10Protos.WrapperProto.newBuilder()
            .setService(
                R10Protos.LaunchMonitorService.newBuilder()
                    .setStartTiltCalRequest(R10Protos.StartTiltCalibrationRequest.getDefaultInstance()),
            )
            .build()

    private fun subscribeAlertsProto(): R10Protos.WrapperProto {
        val alert = R10Protos.AlertMessage.newBuilder()
            .setType(R10Protos.AlertNotification.AlertType.LAUNCH_MONITOR)
            .build()
        val sub = R10Protos.SubscribeRequest.newBuilder().addAlerts(alert).build()
        return R10Protos.WrapperProto.newBuilder()
            .setEvent(R10Protos.EventSharing.newBuilder().setSubscribeRequest(sub))
            .build()
    }

    private fun shotConfigProto(): R10Protos.WrapperProto {
        val teeRangeM = config.teeDistanceFt / 3.281f
        val cfg = R10Protos.ShotConfigRequest.newBuilder()
            .setTemperature(config.temperatureF)
            .setHumidity(config.humidity)
            .setAltitude(config.altitudeM)
            .setAirDensity(config.airDensity)
            .setTeeRange(teeRangeM)
            .build()
        return R10Protos.WrapperProto.newBuilder()
            .setService(R10Protos.LaunchMonitorService.newBuilder().setShotConfigRequest(cfg))
            .build()
    }

    companion object {
        /** Battery level = value byte 0, unsigned (DESIGN §7.1 step 3). */
        fun batteryPercent(bytes: ByteArray): Int =
            if (bytes.isEmpty()) -1 else bytes[0].toInt() and 0xFF

        fun ByteArray.ascii(): String = String(this, Charsets.US_ASCII).trim('\u0000', ' ', '\r', '\n')
    }
}

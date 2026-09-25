package com.techdelivery.r10.protocol

import LaunchMonitor.Proto.R10Protos
import com.techdelivery.r10.protocol.alert.AlertRouter
import com.techdelivery.r10.protocol.alert.DeviceAlert
import com.techdelivery.r10.protocol.shot.Shot
import com.techdelivery.r10.protocol.shot.ShotDeduper
import com.techdelivery.r10.protocol.transport.Transport
import com.techdelivery.r10.protocol.wire.GattUuids
import com.techdelivery.r10.protocol.wire.WireConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Settings the setup sequence needs (subset of AppSettings relevant to the device). */
data class DeviceSetupConfig(
    val temperatureF: Float = 60f,
    val humidity: Float = 1f,
    val altitudeM: Float = 0f,
    val airDensity: Float = 1f,
    val teeDistanceFt: Int = 7,
    val calibrateTiltOnConnect: Boolean = false,
    /** §7.2: on a STANDBY state alert, send WakeUpRequest instead of only notifying. */
    val autoWake: Boolean = true,
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

    private val deduper = ShotDeduper()

    /**
     * Every decoded device alert, including ones this layer does not act on.
     *
     * DROP_OLDEST: this stream is a transient UI mirror of state/error/calibration.
     * A stale alert is worthless, and the pump must never suspend on it — see
     * [pumpAlerts] for why that matters.
     */
    private val _alerts = MutableSharedFlow<DeviceAlert>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val alerts: Flow<DeviceAlert> = _alerts.asSharedFlow()

    /**
     * Live `alerts` subscriber count. Exposed because "is anyone actually listening"
     * is worth observing in its own right, and because a test that wants to prove the
     * pump is unaffected by a wedged subscriber cannot rely on scheduler ordering
     * to establish that the subscription landed.
     */
    val alertSubscriberCount: StateFlow<Int> get() = _alerts.subscriptionCount

    /**
     * Deduplicated shots (DESIGN §7.2). Newest are emitted as they arrive.
     *
     * Left on the default SUSPEND policy on purpose: this flow feeds persistence, so
     * dropping is not acceptable. The pump keeps it from ever filling by making the
     * consuming side fast (the service hands disk writes to `Dispatchers.IO`).
     */
    private val _shots = MutableSharedFlow<Shot>(extraBufferCapacity = 64)
    val shots: Flow<Shot> = _shots.asSharedFlow()

    /** In-flight `wakeUp()` so repeated STANDBY alerts cannot fan out. */
    private var wakeJob: Job? = null

    /**
     * `wakeJob` is written from the pump coroutine and from `connect()` via
     * [resetForNewConnection], so both accesses are guarded — a plain `var` would
     * let the pump read a stale non-null value and suppress a legitimate wake.
     */
    private val wakeLock = Any()

    /** Last device state seen, from the §7.1 step-7 StatusResponse or a state alert. */
    private val _lastStateType = MutableStateFlow<R10Protos.State.StateType?>(null)
    val lastStateType: StateFlow<R10Protos.State.StateType?> = _lastStateType.asStateFlow()

    /**
     * Drain `engine.eventNotification` (B313) into [alerts] / [shots], applying
     * the §7.2 policies: shot dedup by `shot_id`, auto-wake on STANDBY.
     * Auto-wake is launched separately so a slow/failed wake cannot stall the pump.
     */
    fun pumpAlerts(scope: CoroutineScope): Job = scope.launch {
        engine.eventNotification.collect { wrapper ->
            for (alert in AlertRouter.route(wrapper)) {
                when (alert) {
                    is DeviceAlert.StateChanged -> {
                        _lastStateType.value = alert.state
                        if (alert.state == R10Protos.State.StateType.STANDBY && config.autoWake) {
                            // Single-flight. A device that re-announces STANDBY every
                            // second used to spawn one WakeUpRequest coroutine per
                            // alert, piling up unbounded pending requests.
                            synchronized(wakeLock) {
                                if (wakeJob?.isActive != true) {
                                    wakeJob = scope.launch { wakeUp() }
                                }
                            }
                        }
                    }

                    is DeviceAlert.ShotAlert -> {
                        // §7.2 dedup by shot_id. A frame that carried no shot_id
                        // cannot be deduped: `getShotId()` would report 0 and every
                        // id-less shot would be dropped as a duplicate of the first.
                        // Prefer a possible duplicate over a lost real shot.
                        val fresh = !alert.hasDeviceShotId || deduper.accept(alert.shot.shotId)
                        if (fresh) _shots.emit(alert.shot)
                    }

                    else -> Unit
                }
                // tryEmit, never emit: DROP_OLDEST means this cannot suspend.
                _alerts.tryEmit(alert)
            }
        }
    }

    /** Per-connection reset: the R10 restarts its shot-id sequence on power cycle. */
    fun resetForNewConnection() {
        deduper.reset()
        _lastStateType.value = null
        synchronized(wakeLock) {
            wakeJob?.cancel()
            wakeJob = null
        }
    }

    /**
     * Steps 1-5: connect, subscribe measurement/control/status, read device info
     * + battery, subscribe the data-channel notifier, then handshake.
     * Returns true if the handshake completed within [WireConstants.HANDSHAKE_TIMEOUT_MS].
     */
    suspend fun connect(): Boolean {
        resetForNewConnection()
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

    suspend fun statusRequest(): ResponseEvent? {
        val ev = engine.sendProtobufRequest(statusProto())
        ev?.proto?.takeIf { it.hasService() }
            ?.service?.takeIf { it.hasStatusResponse() }
            ?.statusResponse?.takeIf { it.hasState() }
            ?.state?.let { _lastStateType.value = it.state }
        return ev
    }

    suspend fun tiltRequest(): ResponseEvent? = engine.sendProtobufRequest(tiltProto())
    suspend fun subscribeAlerts(): ResponseEvent? = engine.sendProtobufRequest(subscribeAlertsProto())
    suspend fun startTiltCalibration(): ResponseEvent? = engine.sendProtobufRequest(startTiltCalProto())
    suspend fun sendShotConfig(): ResponseEvent? = engine.sendProtobufRequest(shotConfigProto())

    // --- Proto builders ---

    private fun wakeUpProto(): R10Protos.WrapperProto = R10Protos.WrapperProto.newBuilder()
        .setService(
            R10Protos.LaunchMonitorService.newBuilder().setWakeUpRequest(
                R10Protos.WakeUpRequest.getDefaultInstance(),
            ),
        )
        .build()

    private fun statusProto(): R10Protos.WrapperProto = R10Protos.WrapperProto.newBuilder()
        .setService(
            R10Protos.LaunchMonitorService.newBuilder().setStatusRequest(
                R10Protos.StatusRequest.getDefaultInstance(),
            ),
        )
        .build()

    private fun tiltProto(): R10Protos.WrapperProto = R10Protos.WrapperProto.newBuilder()
        .setService(
            R10Protos.LaunchMonitorService.newBuilder().setTiltRequest(R10Protos.TiltRequest.getDefaultInstance()),
        )
        .build()

    private fun startTiltCalProto(): R10Protos.WrapperProto = R10Protos.WrapperProto.newBuilder()
        .setService(
            R10Protos.LaunchMonitorService.newBuilder()
                .setStartTiltCalRequest(R10Protos.StartTiltCalibrationRequest.getDefaultInstance()),
        )
        .build()

    private fun subscribeAlertsProto(): R10Protos.WrapperProto = AlertRouter.launchMonitorSubscribeWrapper()

    private fun shotConfigProto(): R10Protos.WrapperProto = R10Protos.WrapperProto.newBuilder()
        .setService(
            R10Protos.LaunchMonitorService.newBuilder().setShotConfigRequest(shotConfigRequest(config)),
        )
        .build()

    companion object {
        /** Build the §7.1 step-11 ShotConfigRequest from setup config. */
        fun shotConfigRequest(config: DeviceSetupConfig): R10Protos.ShotConfigRequest {
            val teeRangeM = config.teeDistanceFt / 3.281f
            return R10Protos.ShotConfigRequest.newBuilder()
                .setTemperature(config.temperatureF)
                .setHumidity(config.humidity)
                .setAltitude(config.altitudeM)
                .setAirDensity(config.airDensity)
                .setTeeRange(teeRangeM)
                .build()
        }

        /** Battery level = value byte 0, unsigned (DESIGN §7.1 step 3). */
        fun batteryPercent(bytes: ByteArray): Int = if (bytes.isEmpty()) -1 else bytes[0].toInt() and 0xFF

        fun ByteArray.ascii(): String = String(this, Charsets.US_ASCII).trim('\u0000', ' ', '\r', '\n')
    }
}

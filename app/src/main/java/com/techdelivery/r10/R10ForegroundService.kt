package com.techdelivery.r10

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.techdelivery.r10.ble.BleTransportImpl
import com.techdelivery.r10.protocol.DeviceSetupConfig
import com.techdelivery.r10.protocol.ProtocolEngine
import com.techdelivery.r10.protocol.R10Device
import com.techdelivery.r10.protocol.transport.TransportState
import com.techdelivery.r10.settings.SettingsDataStore
import com.techdelivery.r10.data.ShotCsvStore
import com.techdelivery.r10.data.ShotPersistSink
import com.techdelivery.r10.state.AlertMirror
import com.techdelivery.r10.state.ConnState
import com.techdelivery.r10.state.DeviceStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Foreground service that owns the R10 connection graph (manual DI, DESIGN §2/§3):
 * SettingsRepository -> BleTransportImpl -> ProtocolEngine -> R10Device.
 * Runs the §7.1 setup and mirrors state into [DeviceStateHolder] for the UI.
 * START_STICKY restart tears down and reruns the full sequence from scratch.
 */
class R10ForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "r10_monitor_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.techdelivery.r10.action.START"
        const val ACTION_STOP = "com.techdelivery.r10.action.STOP"
        private const val TAG = "R10DIAG"

        /** Depth of the in-memory queue feeding the disk writer. */
        private const val PERSIST_QUEUE_CAPACITY = 512

        /** How long [onDestroy] waits for that queue to drain before giving up. */
        private const val PERSIST_DRAIN_MS = 2_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var deviceJob: Job? = null
    private var transport: BleTransportImpl? = null
    private var startedForeground = false

    /**
     * Owns the shot -> disk handoff (queue, IO writer, failure signal). Kept off
     * [deviceJob] so a reconnect cannot strand queued rows. See [ShotPersistSink].
     */
    private var persistSink: ShotPersistSink? = null

    private fun ensurePersistSink(store: ShotCsvStore): ShotPersistSink =
        persistSink?.takeIf { it.isRunning } ?: ShotPersistSink(store::append, scope, PERSIST_QUEUE_CAPACITY)
            .also {
                persistSink = it
                it.start()
            }

    override fun onCreate() {
        super.onCreate()
        // Mirror every TX/RX to logcat in golden format:
        //   adb logcat -s R10HEX -v raw > session-r10.hex
        scope.launch {
            DeviceStateHolder.hexFlow.collect { e ->
                Log.d("R10HEX", "${e.direction.name} ${e.bytes.joinToString(" ") { "%02X".format(it) }}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        startAsForeground()
        startDevice()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startDevice() {
        deviceJob?.cancel()
        // Connection-scoped state only. `shots` / `shotCount` hold persisted CSV
        // history and must survive a Start.
        DeviceStateHolder.resetConnection()
        deviceJob = scope.launch {
            try {
                val settings = (application as R10App).settingsRepository.settings.first()
                Log.i(TAG, "settings loaded: name='${settings.deviceName}' tee=${settings.teeDistanceFt}ft calib=${settings.calibrateTiltOnConnect}")
                val adapter = getSystemService(BluetoothManager::class.java)?.adapter
                    ?: error("Bluetooth unavailable")
                Log.i(TAG, "adapter=${adapter.name} enabled=${adapter.isEnabled} leScanner=${adapter.bluetoothLeScanner != null}")

                val transport = BleTransportImpl(
                    context = this@R10ForegroundService,
                    adapter = adapter,
                    deviceName = settings.deviceName,
                    reconnectIntervalMs = settings.reconnectIntervalS * 1000L,
                )
                this@R10ForegroundService.transport = transport
                val engine = ProtocolEngine(transport, hexLog = DeviceStateHolder.hexLog, scope = this)
                val config = DeviceSetupConfig(
                    temperatureF = settings.temperature.toFloat(),
                    humidity = settings.humidity.toFloat(),
                    altitudeM = settings.altitude.toFloat(),
                    airDensity = settings.airDensity.toFloat(),
                    teeDistanceFt = settings.teeDistanceFt,
                    calibrateTiltOnConnect = settings.calibrateTiltOnConnect,
                    // §7.2 auto-wake must come from the persisted setting; the
                    // DeviceSetupConfig default would silently override the
                    // Settings toggle if this line were missing.
                    autoWake = settings.autoWake,
                )
                val device = R10Device(transport, engine, config)

                // Mirror transport state -> ConnState (until handshake flips READY).
                launch {
                    transport.state.collect { st ->
                        DeviceStateHolder.connectionState.value = when (st) {
                            TransportState.SCANNING -> ConnState.SCANNING
                            TransportState.CONNECTING -> ConnState.CONNECTING
                            TransportState.CONNECTED ->
                                if (DeviceStateHolder.connectionState.value == ConnState.READY) ConnState.READY
                                else ConnState.HANDSHAKE
                            TransportState.DISCONNECTED -> ConnState.ERROR
                            else -> DeviceStateHolder.connectionState.value
                        }
                    }
                }
                // Device-pushed alerts (B313) -> UI state. The §7.2 policies
                // (shot dedup by shot_id, auto-wake on STANDBY) live in
                // R10Device.pumpAlerts; this layer only surfaces the result.
                device.pumpAlerts(this)
                launch {
                    device.alerts.collect { AlertMirror.apply(it) }
                }
                val sink = ensurePersistSink((application as R10App).shotStore)
                launch { sink.error.collect { DeviceStateHolder.historyError.value = it } }
                launch {
                    // Fast consumer only. Nothing in this collector may block: a slow
                    // subscriber backpressures R10Device.shots, then
                    // ProtocolEngine._events, then the BLE inbound reader. Disk work
                    // belongs to the sink's IO writer, not here.
                    device.shots.collect { shot ->
                        DeviceStateHolder.addShot(shot)
                        sink.submit(shot)
                    }
                }

                // Persistent notification: connection state + device state + shot count + battery.
                launch {
                    combine(
                        DeviceStateHolder.connectionState,
                        DeviceStateHolder.stateType,
                        DeviceStateHolder.shotCount,
                        DeviceStateHolder.deviceInfo,
                    ) { conn, st, count, info ->
                        val batt = if (info.batteryLevel >= 0) " · ${info.batteryLevel}%" else ""
                        val shotTxt = if (count > 0) " · $count shots" else ""
                        "$conn${st?.let { " · $it" } ?: ""}$shotTxt$batt"
                    }.collect { updateNotification(it) }
                }

                DeviceStateHolder.connectionState.value = ConnState.SCANNING
                Log.i(TAG, "starting §7.1 setup (device.connect)")
                if (!device.connect()) {
                    Log.w(TAG, "handshake did not complete")
                    DeviceStateHolder.errorMessage.value = "Handshake timed out"
                    DeviceStateHolder.connectionState.value = ConnState.ERROR
                    return@launch
                }
                Log.i(TAG, "handshake complete, deviceInfo=${device.deviceInfo.value}")
                DeviceStateHolder.deviceInfo.value = device.deviceInfo.value

                // Steps 6-11 with readouts captured for the UI.
                Log.i(TAG, "step: wakeUp")
                device.wakeUp()?.let { DeviceStateHolder.wakeUpStatus.value = it.proto.service?.wakeUpResponse?.status?.name }
                Log.i(TAG, "step: statusRequest")
                device.statusRequest()?.let { DeviceStateHolder.stateType.value = it.proto.service?.statusResponse?.state?.state?.name }
                Log.i(TAG, "step: tiltRequest")
                device.tiltRequest()?.let {
                    val t = it.proto.service?.tiltResponse?.tilt
                    if (t != null) DeviceStateHolder.tilt.value = "roll=${t.roll} pitch=${t.pitch}"
                }
                Log.i(TAG, "step: subscribeAlerts")
                device.subscribeAlerts()
                if (config.calibrateTiltOnConnect) { Log.i(TAG, "step: startTiltCalibration"); device.startTiltCalibration() }
                Log.i(TAG, "step: sendShotConfig")
                device.sendShotConfig()

                Log.i(TAG, "§7.1 setup complete -> READY")
                DeviceStateHolder.connectionState.value = ConnState.READY
            } catch (e: Exception) {
                Log.e(TAG, "setup failed: ${e.message}", e)
                DeviceStateHolder.errorMessage.value = e.message ?: "connection error"
                DeviceStateHolder.connectionState.value = ConnState.ERROR
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "R10 Monitor connection" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun startAsForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification("Connecting…"), type)
        startedForeground = true
    }

    /** M3: keep the ongoing notification live (state + battery + shot count). */
    private fun updateNotification(text: String) {
        if (!startedForeground) return
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    override fun onDestroy() {
        deviceJob?.cancel()
        // Give queued shots a bounded window to reach disk before the scope dies.
        val drained = runBlocking {
            runCatching { persistSink?.close(PERSIST_DRAIN_MS) ?: true }.getOrDefault(false)
        }
        if (!drained) Log.e(TAG, "persist writer did not drain in ${PERSIST_DRAIN_MS}ms; queued shots lost")
        // Close the GATT link before tearing down the scope. Without this the
        // BluetoothGatt is never closed, so every Stop->Start cycle leaked another
        // live connection — observed on hardware as every notification being
        // delivered twice from two different threads.
        runBlocking { runCatching { transport?.stop() } }
        transport = null
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}

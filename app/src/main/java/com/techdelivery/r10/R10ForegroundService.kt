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
import com.techdelivery.r10.state.ConnState
import com.techdelivery.r10.state.DeviceStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var deviceJob: Job? = null

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
        DeviceStateHolder.reset()
        deviceJob = scope.launch {
            try {
                val settings = SettingsDataStore.create(this@R10ForegroundService).settings.first()
                val adapter = getSystemService(BluetoothManager::class.java)?.adapter
                    ?: error("Bluetooth unavailable")

                val transport = BleTransportImpl(
                    context = this@R10ForegroundService,
                    adapter = adapter,
                    deviceName = settings.deviceName,
                    reconnectIntervalMs = settings.reconnectIntervalS * 1000L,
                )
                val engine = ProtocolEngine(transport, hexLog = DeviceStateHolder.hexLog, scope = this)
                val config = DeviceSetupConfig(
                    temperatureF = settings.temperature.toFloat(),
                    humidity = settings.humidity.toFloat(),
                    altitudeM = settings.altitude.toFloat(),
                    airDensity = settings.airDensity.toFloat(),
                    teeDistanceFt = settings.teeDistanceFt,
                    calibrateTiltOnConnect = settings.calibrateTiltOnConnect,
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
                // Device-pushed alerts (B3) -> surface latest state type if present.
                launch {
                    engine.eventNotification.collect { proto ->
                        proto.service?.statusResponse?.state?.state?.let {
                            DeviceStateHolder.stateType.value = it.name
                        }
                    }
                }

                DeviceStateHolder.connectionState.value = ConnState.SCANNING
                if (!device.connect()) {
                    DeviceStateHolder.errorMessage.value = "Handshake timed out"
                    DeviceStateHolder.connectionState.value = ConnState.ERROR
                    return@launch
                }
                DeviceStateHolder.deviceInfo.value = device.deviceInfo.value

                // Steps 6-11 with readouts captured for the UI.
                device.wakeUp()?.let { DeviceStateHolder.wakeUpStatus.value = it.proto.service?.wakeUpResponse?.status?.name }
                device.statusRequest()?.let { DeviceStateHolder.stateType.value = it.proto.service?.statusResponse?.state?.state?.name }
                device.tiltRequest()?.let {
                    val t = it.proto.service?.tiltResponse?.tilt
                    if (t != null) DeviceStateHolder.tilt.value = "roll=${t.roll} pitch=${t.pitch}"
                }
                device.subscribeAlerts()
                if (config.calibrateTiltOnConnect) device.startTiltCalibration()
                device.sendShotConfig()

                DeviceStateHolder.connectionState.value = ConnState.READY
            } catch (e: Exception) {
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
    }

    override fun onDestroy() {
        deviceJob?.cancel()
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}

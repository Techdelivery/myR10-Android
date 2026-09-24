package com.techdelivery.r10.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.techdelivery.r10.protocol.transport.Transport
import com.techdelivery.r10.protocol.transport.TransportState
import com.techdelivery.r10.protocol.util.SingleFlight
import com.techdelivery.r10.protocol.wire.GattUuids
import com.techdelivery.r10.protocol.wire.WireConstants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID

/**
 * Android BluetoothGatt implementation of [Transport] (DESIGN §4, §7.1).
 *
 * - Scans by advertised name (from settings), bonds if unbonded, connects with a
 *   retry loop, discovers services.
 * - All GATT operations are funnelled through one [SingleFlight] because GATT
 *   allows only a single outstanding operation.
 * - Notifications are surfaced raw (header byte intact) on [incoming].
 *
 * Permissions (BLUETOOTH_SCAN/CONNECT) are the caller's responsibility; the
 * runtime flow is wired in G5.
 */
@SuppressLint("MissingPermission")
class BleTransportImpl(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val deviceName: String,
    private val reconnectIntervalMs: Long = WireConstants.HANDSHAKE_TIMEOUT_MS,
) : Transport {

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    override val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

    private val _state = MutableStateFlow(TransportState.DISCONNECTED)
    override val state: Flow<TransportState> = _state.asStateFlow()

    private val flight = SingleFlight()

    @Volatile private var gatt: BluetoothGatt? = null
    private var connectDeferred: CompletableDeferred<Unit>? = null
    private var opDeferred: CompletableDeferred<Unit>? = null
    private var readDeferred: CompletableDeferred<ByteArray>? = null

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _state.value = TransportState.CONNECTED
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _state.value = TransportState.DISCONNECTED
                    connectDeferred?.completeExceptionally(IOException("gatt disconnected status=$status"))
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            connectDeferred?.complete(Unit)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            c.value?.let { _incoming.tryEmit(it) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) opDeferred?.complete(Unit)
            else opDeferred?.completeExceptionally(IOException("write status=$status"))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) opDeferred?.complete(Unit)
            else opDeferred?.completeExceptionally(IOException("desc write status=$status"))
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) readDeferred?.complete(c.value ?: ByteArray(0))
            else readDeferred?.completeExceptionally(IOException("read status=$status"))
        }
    }

    override suspend fun start() {
        _state.value = TransportState.SCANNING
        val device = scanForDevice() ?: throw IOException("device '$deviceName' not found")

        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            bond(device)
        }

        _state.value = TransportState.CONNECTING
        connectWithRetry(device)
    }

    override suspend fun stop() {
        withContext(Dispatchers.IO) {
            gatt?.close()
        }
        gatt = null
        _state.value = TransportState.DISCONNECTED
    }

    override suspend fun write(chunk: ByteArray) = flight.run {
        val g = requireGatt()
        val ch = findCharacteristicIn(GattUuids.DEVICE_INTERFACE_SERVICE, GattUuids.DATA_WRITER)
            ?: throw IOException("data writer characteristic not found")
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ch.value = chunk
        opDeferred = CompletableDeferred()
        withContext(Dispatchers.IO) {
            if (!g.writeCharacteristic(ch)) throw IOException("writeCharacteristic() returned false")
        }
        withTimeout(WireConstants.REQUEST_TIMEOUT_MS) { opDeferred!!.await() }
    }

    override suspend fun subscribe(uuid: UUID) = flight.run {
        val g = requireGatt()
        val ch = findCharacteristic(uuid) ?: throw IOException("char $uuid not found")
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD_UUID) ?: throw IOException("no CCCD on $uuid")
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        opDeferred = CompletableDeferred()
        withContext(Dispatchers.IO) {
            if (!g.writeDescriptor(cccd)) throw IOException("writeDescriptor() returned false")
        }
        withTimeout(WireConstants.REQUEST_TIMEOUT_MS) { opDeferred!!.await() }
    }

    override suspend fun read(uuid: UUID): ByteArray = flight.run {
        val g = requireGatt()
        val ch = findCharacteristic(uuid) ?: throw IOException("char $uuid not found")
        readDeferred = CompletableDeferred()
        withContext(Dispatchers.IO) {
            if (!g.readCharacteristic(ch)) throw IOException("readCharacteristic() returned false")
        }
        withTimeout(WireConstants.REQUEST_TIMEOUT_MS) { readDeferred!!.await() }
    }

    // --- internals ---

    private fun requireGatt(): BluetoothGatt = gatt ?: throw IllegalStateException("not connected")

    private fun findCharacteristic(uuid: UUID): BluetoothGattCharacteristic? {
        val g = gatt ?: return null
        for (svc in g.services) {
            svc.getCharacteristic(uuid)?.let { return it }
        }
        return null
    }

    private fun findCharacteristicIn(serviceUuid: UUID, charUuid: UUID): BluetoothGattCharacteristic? =
        gatt?.getService(serviceUuid)?.getCharacteristic(charUuid)

    private suspend fun scanForDevice(): BluetoothDevice? = withContext(Dispatchers.IO) {
        val scanner = adapter.bluetoothLeScanner ?: return@withContext null
        val deferred = CompletableDeferred<BluetoothDevice?>()
        val filter = ScanFilter.Builder()
            .setDeviceName(deviceName)
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device ?: return
                if (d.name == deviceName || d.address.equals(deviceName, ignoreCase = true)) {
                    if (!deferred.isCompleted) deferred.complete(d)
                }
            }
            override fun onScanFailed(errorCode: Int) {
                if (!deferred.isCompleted) deferred.completeExceptionally(IOException("scan failed $errorCode"))
            }
        }
        try {
            scanner.startScan(listOf(filter), settings, callback)
            withTimeout(SCAN_TIMEOUT_MS) { deferred.await() }
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
    }

    private suspend fun bond(device: BluetoothDevice) = withContext(Dispatchers.IO) {
        val deferred = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                when (d.bondState) {
                    BluetoothDevice.BOND_BONDED -> if (!deferred.isCompleted) deferred.complete(Unit)
                    BluetoothDevice.BOND_NONE -> if (!deferred.isCompleted)
                        deferred.completeExceptionally(IOException("bonding failed"))
                }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        try {
            device.createBond()
            withTimeout(BOND_TIMEOUT_MS) { deferred.await() }
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private suspend fun connectWithRetry(device: BluetoothDevice) {
        var attempt = 0
        while (true) {
            attempt++
            try {
                connectOnce(device)
                return
            } catch (e: Exception) {
                if (attempt >= MAX_CONNECT_ATTEMPTS) throw e
                delay(reconnectIntervalMs)
            }
        }
    }

    private suspend fun connectOnce(device: BluetoothDevice) = withContext(Dispatchers.IO) {
        val deferred = CompletableDeferred<Unit>()
        connectDeferred = deferred
        val g = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("connectGatt returned null")
        try {
            withTimeout(CONNECT_TIMEOUT_MS) { deferred.await() }
            gatt = g
        } catch (e: Exception) {
            runCatching { g.close() }
            connectDeferred = null
            throw e
        }
    }

    companion object {
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_TIMEOUT_MS = 15_000L
        private const val BOND_TIMEOUT_MS = 30_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val MAX_CONNECT_ATTEMPTS = 5
    }
}

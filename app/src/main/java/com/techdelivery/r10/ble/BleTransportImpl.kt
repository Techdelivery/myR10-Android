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
import android.os.ParcelUuid
import android.util.Log
import com.techdelivery.r10.protocol.transport.Transport
import com.techdelivery.r10.protocol.transport.TransportState
import com.techdelivery.r10.protocol.util.SingleFlight
import com.techdelivery.r10.protocol.wire.GattUuids
import com.techdelivery.r10.protocol.wire.WireConstants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
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
            Log.i(TAG, "gatt onConnectionStateChange status=$status newState=$newState")
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
            Log.i(TAG, "gatt onServicesDiscovered status=$status services=${g.services?.size ?: -1}")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectDeferred?.completeExceptionally(IOException("service discovery status=$status"))
            } else {
                connectDeferred?.complete(Unit)
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            c.value?.let {
                Log.d(TAG, "notify ${c.uuid} ${it.size}B hex=${it.joinToString(" ") { b -> "%02X".format(b) }}")
                _incoming.tryEmit(it)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                opDeferred?.complete(Unit)
            } else {
                Log.w(TAG, "char write FAILED ${c.uuid} status=$status")
                opDeferred?.completeExceptionally(IOException("write status=$status"))
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                opDeferred?.complete(Unit)
            } else {
                Log.w(TAG, "descriptor write FAILED ${d.uuid} status=$status")
                opDeferred?.completeExceptionally(IOException("desc write status=$status"))
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                readDeferred?.complete(c.value ?: ByteArray(0))
            } else {
                Log.w(TAG, "char read FAILED ${c.uuid} status=$status")
                readDeferred?.completeExceptionally(IOException("read status=$status"))
            }
        }
    }

    override suspend fun start() {
        _state.value = TransportState.SCANNING
        // DESIGN §2 path 1: an already-bonded peripheral is connected directly by its
        // identity address. A bonded R10 typically STOPS advertising, so scan-only
        // discovery can never find it again (observed: 0 adv packets post-pairing).
        val bonded = findBondedDevice()
        val device = bonded ?: run {
            Log.i(TAG, "no bonded '$deviceName', falling back to scan")
            scanForDevice()
                ?: throw IOException("device '$deviceName' not found (not bonded, not advertising)")
        }
        Log.i(
            TAG,
            "target ${device.address} bondState=${device.bondState} via=${if (bonded != null) "bonded-direct" else "scan"}",
        )

        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            Log.i(TAG, "bonding with ${device.address}")
            bond(device)
            Log.i(TAG, "bonded")
        }

        _state.value = TransportState.CONNECTING
        connectWithRetry(device)
        Log.i(TAG, "connectWithRetry returned, gatt=${gatt != null}")
    }

    /**
     * DESIGN §2 path 1: reuse a device already paired via system Settings.
     * Matches on the cached GATT name — the R10 puts no local name in its
     * advertisement, so this cache is the only place the name exists.
     */
    @SuppressLint("MissingPermission")
    private fun findBondedDevice(): BluetoothDevice? =
        adapter.bondedDevices.orEmpty().firstOrNull { it.name == deviceName }
            .also { Log.i(TAG, "bonded lookup '$deviceName' -> ${it?.address ?: "none"}") }

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

    private fun requireGatt(): BluetoothGatt = gatt ?: error("not connected")

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
        // Filter on R10-specific identifiers only. Measured 2026-09-24 from a real
        // UNPAIRED unit in pairing mode: it advertises name="Approach R10" and
        // service UUID 0xFE1F, and NEVER the 6A4E2800 GATT data service. An earlier
        // revision filtered on DEVICE_INTERFACE_SERVICE here, which matches nothing.
        // Both branches below are R10-specific, so the worst case is "not found",
        // never "connected to some other nearby peripheral".
        val filters: List<ScanFilter> = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(GattUuids.ADVERTISED_SERVICE))
                .build(),
            ScanFilter.Builder().setDeviceName(deviceName).build(),
        )
        Log.i(TAG, "startScan filters=[ServiceUuid=${GattUuids.ADVERTISED_SERVICE} or Name=$deviceName]")
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device ?: return
                val rec = result.scanRecord
                Log.i(
                    TAG,
                    "adv name='${d.name}' addr=${d.address} bond=${d.bondState} " +
                        "svcUuids=${rec?.serviceUuids} raw=${rec?.bytes?.joinToString("") { "%02X".format(it) }}",
                )
                if (d.name == deviceName || d.address.equals(deviceName, ignoreCase = true)) {
                    if (!deferred.isCompleted) deferred.complete(d)
                }
            }
            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "BLE scan FAILED errorCode=$errorCode")
                if (!deferred.isCompleted) deferred.completeExceptionally(IOException("scan failed $errorCode"))
            }
        }
        try {
            scanner.startScan(filters, settings, callback)
            withTimeout(SCAN_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "scan timed out after ${SCAN_TIMEOUT_MS}ms")
            null
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
    }

    private suspend fun bond(device: BluetoothDevice) = withContext(Dispatchers.IO) {
        val deferred = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                Log.i(TAG, "bond broadcast addr=${d?.address} state=${d?.bondState}")
                when (d?.bondState) {
                    BluetoothDevice.BOND_BONDED -> if (!deferred.isCompleted) deferred.complete(Unit)

                    BluetoothDevice.BOND_NONE -> if (!deferred.isCompleted) {
                        deferred.completeExceptionally(IOException("bonding failed"))
                    }
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
            val started = device.createBond()
            Log.i(TAG, "createBond() returned $started")
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
                // DESIGN §11: retry with exponential backoff (GATT 133 and friends).
                // connectOnce already closed the GATT client, so the next attempt
                // starts from a clean slate rather than a half-open link.
                val wait = backoffDelayMs(reconnectIntervalMs, attempt)
                Log.w(TAG, "connect attempt $attempt failed (${e.message}); retrying in ${wait}ms")
                delay(wait)
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

        /**
         * Exponential backoff for reconnect attempts (DESIGN §11): base doubles per
         * attempt and is capped at [capMs]. A non-positive base collapses to the cap
         * so a misconfigured setting cannot produce a hot retry loop.
         *
         * Growth is done in `Double`, not `shl`. `baseMs shl n` wraps negative once
         * the product passes 2^63; the old trailing `coerceAtLeast(1)` then turned
         * that wrap into a 1 ms hot retry — the exact failure this function exists
         * to prevent. `Double` saturates to +Inf instead, which lands on the cap.
         */
        fun backoffDelayMs(baseMs: Long, attempt: Int, capMs: Long = 60_000L): Long {
            require(attempt >= 1) { "attempt is 1-based" }
            require(capMs >= 1L) { "capMs must be at least 1" }
            if (baseMs <= 0L) return capMs
            if (baseMs >= capMs) return capMs
            val grown = baseMs.toDouble() * Math.pow(2.0, (attempt - 1).coerceAtMost(62).toDouble())
            if (grown.isInfinite() || grown >= capMs.toDouble()) return capMs
            return grown.toLong().coerceAtLeast(1L)
        }
        const val TAG = "R10DIAG"
        private const val SCAN_TIMEOUT_MS = 15_000L
        private const val BOND_TIMEOUT_MS = 30_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val MAX_CONNECT_ATTEMPTS = 5
    }
}

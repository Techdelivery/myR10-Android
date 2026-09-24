package com.techdelivery.r10

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import java.util.Locale

/**
 * Dev-only, read-only BLE advertisement dump (H4 investigation).
 *
 * Answers one question with evidence instead of guesses: **what does the R10
 * actually put in its advertisement payload?** Specifically, does it advertise
 * the `6A4E2800-…` device-interface service UUID, which is what our production
 * ScanFilter relies on for fresh (unbonded) discovery.
 *
 * Deliberately scans with NO filter and NEVER connects, so it cannot accidentally
 * pair with or grab an unrelated peripheral the way the old DIAG_SCAN_ALL code
 * path could.
 *
 * Run with:
 *   adb shell am start -n com.techdelivery.r10/.ScanDumpActivity
 *   adb logcat -s R10SCAN:V
 */
class ScanDumpActivity : ComponentActivity() {

    private lateinit var handler: Handler
    private var callback: ScanCallback? = null

    @SuppressLint("MissingPermission") // permissions pre-granted for the dev build
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Dev-only tool. Guard so a release build can never be driven into an
        // unfiltered scan by an external intent (the activity is exported for adb).
        if (!BuildConfig.DEBUG) {
            Log.w(TAG, "ScanDumpActivity is debug-only; finishing")
            finish()
            return
        }

        handler = Handler(Looper.getMainLooper())

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "no enabled Bluetooth adapter")
            finish()
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "bluetoothLeScanner is null")
            finish()
            return
        }

        Log.i(TAG, "=== SCAN DUMP START (unfiltered, read-only, ${DUMP_MS / 1000}s) ===")

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = dump(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { dump(it) }
            }
        }
        callback = cb

        scanner.startScan(
            null as List<ScanFilter>?, // no filter: see everything on air
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            cb,
        )

        handler.postDelayed({
            runCatching { scanner.stopScan(cb) }
            Log.i(TAG, "=== SCAN DUMP END (unique=${seen.size}) ===")
            finish()
        }, DUMP_MS)
    }

    private val seen = HashSet<String>()

    @SuppressLint("MissingPermission")
    private fun dump(r: ScanResult) {
        val d = r.device ?: return
        val rec = r.scanRecord ?: return
        val name = rec.deviceName ?: d.name
        val uuids = rec.serviceUuids?.joinToString(",") { it.uuid.toString() } ?: "-"
        val mfg = rec.manufacturerSpecificData?.let { m ->
            buildString {
                for (i in 0 until m.size()) {
                    append("0x%04X".format(m.keyAt(i)))
                    append("=")
                    append(m.valueAt(i)?.joinToString("") { "%02X".format(it) } ?: "")
                    if (i < m.size() - 1) append(" ")
                }
            }
        } ?: "-"
        val advFlags = rec.txPowerLevel?.let { "txp=$it" } ?: "txp=-"

        seen.add(d.address)
        val marker = if (d.address.equals(TARGET, ignoreCase = true)) " <<< R10" else ""
        Log.i(
            TAG,
            "%s name=%-18s rssi=%-5d connectable=%-5s uuids=%s mfg=%s %s%s".format(
                d.address, name ?: "null", r.rssi, r.isConnectable, uuids, mfg, advFlags, marker,
            ),
        )
    }

    companion object {
        private const val TAG = "R10SCAN"
        private const val DUMP_MS = 20_000L
        private const val TARGET = "CE:33:1E:DF:1D:07"
    }
}

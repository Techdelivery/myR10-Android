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
import android.os.ParcelUuid
import android.util.Log
import androidx.activity.ComponentActivity
import com.techdelivery.r10.protocol.wire.GattUuids

/**
 * Dev-only, read-only BLE advertisement dump (H4 / K3 investigation).
 *
 * Answers, with evidence instead of guesses: **do the production `ScanFilter`s
 * match a live R10?**
 *
 * Deliberately scans with NO filter and NEVER connects, so it cannot accidentally
 * pair with or grab an unrelated peripheral the way the old DIAG_SCAN_ALL code
 * path could.
 *
 * Two things this learns the hard way, both baked into the design:
 *
 *  1. The unfiltered and the production-filtered scans run **concurrently**.
 *     Sequential phases race: the R10 advertises in bursts and goes quiet, so a
 *     filtered pass started after the unfiltered pass can begin 16 ms after the
 *     R10's last packet and report "no match" for a device that was on air the
 *     whole time. Concurrent scanning makes the verdict a same-window comparison.
 *  2. Every distinct **raw** R10 advertisement payload is logged once. The parsed
 *     `serviceUuids` field alone hid the fact that the R10 emits more than one
 *     advertisement form: H4 captured `0xFE1F` + 7 manufacturer bytes, while an
 *     idle R10 advertises with **no service UUID at all** (`0E26`, 2 mfg bytes).
 *     A filter keyed on `0xFE1F` cannot match the second form.
 *
 * NOTE (Android behaviour, cost a false "0 devices" verdict): an **unfiltered**
 * scan is refused while the screen is off — `BtScan.ScanManager: Cannot start
 * unfiltered scan in screen-off` — and silently yields nothing. Wake the screen
 * first (`adb shell input keyevent KEYCODE_WAKEUP; adb shell svc power stayon true`).
 * Filtered scans are unaffected.
 *
 * Run with:
 *   adb shell am start -n com.techdelivery.r10/.ScanDumpActivity
 *   adb logcat -s R10SCAN:V
 *
 * Pass `--es filtered false` to skip the production-filter pass.
 *
 * Pass `--es target AA:BB:CC:DD:EE:FF` with your R10's Bluetooth address. The paired
 * unit advertises no name and no service UUID, so its address is the only reliable
 * way to single it out of the raw scan — which is why it is a launch argument here
 * rather than a committed constant. Without it the tool still dumps every packet on
 * air, but the "does the production filter match the R10" verdict is skipped.
 */
class ScanDumpActivity : ComponentActivity() {

    private lateinit var handler: Handler
    private var callback: ScanCallback? = null
    private var filteredCallback: ScanCallback? = null

    @SuppressLint("MissingPermission")
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
        filtered = intent?.getStringExtra("filtered")?.toBoolean() ?: true
        dumpMs = intent?.getLongExtra("duration_ms", DUMP_MS) ?: DUMP_MS
        target = intent?.getStringExtra("target")?.trim().orEmpty()
        // Statics persist across runs in the same process; a stale set would fake a verdict.
        filteredMatches.clear()
        seenPayloads.clear()
        r10Payloads.clear()
        r10SeenUnfiltered = false
        r10Hits = 0

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

        Log.i(
            TAG,
            "=== SCAN DUMP START (unfiltered, ${dumpMs / MS_PER_SECOND}s, filtered=$filtered, target=${target.ifEmpty {
                "(none)"
            }}) ===",
        )

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

        if (filtered) {
            val fcb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val a = result.device?.address ?: return
                    if (filteredMatches.add(a)) {
                        Log.i(
                            TAG,
                            "FILTER MATCH %s name=%s rssi=%d%s".format(
                                a,
                                result.device?.name,
                                result.rssi,
                                if (target.isNotEmpty() &&
                                    a.equals(target, true)
                                ) {
                                    " <<< R10 (production filter WORKS)"
                                } else {
                                    ""
                                },
                            ),
                        )
                    }
                }
            }
            filteredCallback = fcb
            scanner.startScan(
                productionFilters(),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                fcb,
            )
        }

        handler.postDelayed({
            callback?.let { runCatching { scanner.stopScan(it) } }
            filteredCallback?.let { runCatching { scanner.stopScan(it) } }
            Log.i(TAG, "=== DUMP END (unique=${seen.size}) ===")
            report()
            finish()
        }, dumpMs)
    }

    /** The EXACT filters the production transport uses: advertised service OR device name. */
    private fun productionFilters(): List<ScanFilter> = listOf(
        ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(GattUuids.ADVERTISED_SERVICE))
            .build(),
        ScanFilter.Builder().setDeviceName("Approach R10").build(),
    )

    /**
     * The verdict. A miss is only meaningful if the R10 was actually on air in the
     * same window, so the unfiltered pass is the control for it.
     */
    private fun report() {
        Log.i(TAG, "R10 packets seen (unfiltered): $r10Hits, distinct payloads: ${r10Payloads.size}")
        r10Payloads.forEachIndexed { i, raw -> Log.i(TAG, "  PAYLOAD #$i len=${raw.length / 2} $raw") }
        val hit = target.isNotEmpty() && filteredMatches.any { it.equals(target, true) }
        when {
            target.isEmpty() -> Log.w(
                TAG,
                "VERDICT: no target address supplied (pass --es target AA:BB:CC:DD:EE:FF); filter-match check skipped",
            )

            hit -> Log.i(TAG, "VERDICT: production filter finds the R10")

            !r10SeenUnfiltered -> Log.e(TAG, "VERDICT INCONCLUSIVE: R10 was not on air during this run")

            else -> Log.e(
                TAG,
                "VERDICT: production filter did NOT find the R10 although it was on air in the same window",
            )
        }
    }

    private val seen = HashSet<String>()

    @SuppressLint("MissingPermission")
    private fun dump(r: ScanResult) {
        val d = r.device ?: return
        val rec = r.scanRecord ?: return
        val raw = rec.bytes.joinToString("") { "%02X".format(it) }
        seen.add(d.address)

        if (target.isNotEmpty() && d.address.equals(target, ignoreCase = true)) {
            r10SeenUnfiltered = true
            // One line per distinct payload: the R10 is known to emit more than one
            // advertisement form, and which one is on air decides what matches.
            if (r10Payloads.add(raw)) {
                Log.i(
                    TAG,
                    "R10 PAYLOAD #%d name=%s connectable=%s uuids=%s mfg=%s raw=%s".format(
                        r10Payloads.size,
                        rec.deviceName ?: d.name ?: "null",
                        r.isConnectable,
                        rec.serviceUuids?.joinToString(",") { it.uuid.toString() } ?: "-",
                        mfgOf(rec),
                        raw,
                    ),
                )
            }
            r10Hits++
            return // the R10 detail lines above are enough; no per-packet spam
        }

        // One line per distinct (address, payload) so the log stays readable.
        if (seenPayloads.add("${d.address}|$raw")) {
            Log.i(
                TAG,
                "%s name=%-18s rssi=%-5d connectable=%-5s uuids=%s mfg=%s".format(
                    d.address,
                    rec.deviceName ?: d.name ?: "null",
                    r.rssi,
                    r.isConnectable,
                    rec.serviceUuids?.joinToString(",") { it.uuid.toString() } ?: "-",
                    mfgOf(rec),
                ),
            )
        }
    }

    private fun mfgOf(rec: android.bluetooth.le.ScanRecord): String = rec.manufacturerSpecificData?.let { m ->
        buildString {
            for (i in 0 until m.size()) {
                append("0x%04X".format(m.keyAt(i)))
                append("=")
                append(m.valueAt(i)?.joinToString("") { "%02X".format(it) } ?: "")
                if (i < m.size() - 1) append(" ")
            }
        }
    } ?: "-"

    companion object {
        private const val TAG = "R10SCAN"
        private const val DUMP_MS = 25_000L
        private const val MS_PER_SECOND = 1000

        /**
         * The paired R10's Bluetooth address, supplied via `--es target`. Left empty
         * when not provided; the tool then cannot single out the R10 and skips the
         * filter-match verdict. Deliberately not a committed constant: the paired unit
         * advertises no name or service UUID, so its address is the only identifier,
         * and we keep it out of the repo.
         */
        private var target = ""

        /** Overridable via --el duration_ms to watch for advertisement changes over time. */
        private var dumpMs = DUMP_MS

        /** Set false via --es filtered false to skip the production-filter pass. */
        private var filtered = true
        private val filteredMatches = HashSet<String>()
        private val seenPayloads = HashSet<String>()
        private var r10SeenUnfiltered = false
        private var r10Hits = 0
        private val r10Payloads = LinkedHashSet<String>()
    }
}

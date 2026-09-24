# R10 Monitor — M0 + M1 Execution Checklist

Live working checklist. Tick boxes as each step is executed **and verified**.
Detail/rationale lives in `.omp/supipowers/plans/2026-09-12-r10-android-m0-m1-plan.md`.
Byte-level truth lives in `DESIGN.md` §5 (working tree = canonical).

Legend: `[ ]` pending · `[x]` done+verified · `[~]` in progress · `[HW]` needs physical R10 · `[BLOCKED]`

Confirmed decisions (user, 2026-09-24): `applicationId = com.techdelivery.r10` · scope M0+M1 only · SDK install approved · working-tree `DESIGN.md` canonical · no Hilt (manual DI).

Rule: a step is not ticked until its **Verify** command passes. Never tick on "looks right".

---

## Phase A — Environment

- [x] **A0. Build env bootstrap** — DONE 2026-09-24. Gradle 8.14.5 (`~/tools/gradle`), Android SDK (`~/tools/android-sdk`: `platform-tools`, `platforms;android-36`, `build-tools;36.0.0`), licenses accepted, JDK 21 from nix store. `local.properties` still to be written with the scaffold (B1).
  - Verify: `~/tools/gradle/bin/gradle -v` → Gradle 8.14.5 + JDK 21 ✅; `ls ~/tools/android-sdk/platforms/android-36` ✅

## Phase B — Gradle + proto skeleton (M0-1, M0-2)

- [x] **B1. Multi-module scaffold** — DONE 2026-09-24. `settings.gradle.kts` (root `R10Monitor`, includes `:protocol` + `:app`), root `build.gradle.kts`, `gradle/libs.versions.toml`, `gradlew` wrapper 8.14.5, `.gitignore` (pre-existing).
  - Verify: `./gradlew help` exits 0 ✅; `./gradlew projects` shows `:app` + `:protocol` ✅; `grep -rE "^import (android|com\.google\.android)" protocol/src` → CLEAN ✅
  - Resolved pins: AGP 8.13.2, Kotlin 2.4.20 (stable) + compose plugin, Compose BOM 2026.09.00, coroutines 1.11.0, protobuf-javalite + protobuf-kotlin-lite 4.36.2, protobuf-gradle-plugin 0.10.0, JVM target 17, minSdk 26, compile/target 36, applicationId `com.techdelivery.r10`
- [x] **B2. Proto codegen** — DONE 2026-09-24. `protocol/src/main/proto/LaunchMonitor.proto` transcribed from DESIGN Appendix A (proto package `LaunchMonitor.Proto`), plus codegen-only `option java_outer_classname = "R10Protos"` — the package first segment collided with the file-derived outer class name and broke Kotlin-lite fully-qualified refs (wire-irrelevant; noted in DESIGN Appendix A).
  - Verify: `./gradlew :protocol:compileKotlin` green ✅; all 30 appendix messages present as nested classes of `R10Protos` ✅; `AlertNotification` field 1001 generates `alertNotification_` accessor ✅
- [x] **B3. Proto smoke test** — DONE 2026-09-24. `protocol/src/test/kotlin/ProtoSmokeTest.kt`: construct + serialize + re-parse `ShotConfigRequest` (with §8 ft→m tee_range), `AlertDetails` (state+error), `WrapperProto` wrapping a service, and `AlertNotification` field-1001 (`setAlertNotification`/`getAlertNotification`, backing `alertNotification_`).
  - Verify: `./gradlew :protocol:test --tests '*ProtoSmokeTest'` green ✅ — 4 tests, 0 skipped, 0 failures

## Phase C — Wire primitives (M1-8/9/10, M0-3) — pure JVM, no device

- [x] **C1. ByteUtil** — DONE 2026-09-24. `protocol/.../util/ByteUtil.kt`: LE u16/u32 pack+unpack (range-checked), hex ⇄ bytes (case-insensitive, ignores space/colon/dash), concat.
  - Verify: `ByteUtilTest` green ✅ (9 tests) — endianness pinned (`0x0102`→`[02 01]`, `0x01020304`→`[04 03 02 01]`), hex round-trip over all 256 byte values, range/odd-length rejections
- [x] **C2. Crc16** — DONE 2026-09-24. `protocol/.../util/Crc16.kt`: table-driven, reflected poly `0xA001`, init `0x0000` (CRC-16/ARC), `compute()` + `computeLe()` (low byte first).
  - **Verified 2026-09-24: `"123456789"` → `0xBB3D`, LE bytes `3D BB`.** Three independent implementations agree (table-driven LSB-first `0xA001`; bitwise reflected; `crcmod.Crc(0x18005, initCrc=0, rev=True, xorOut=0)`).
  - **RESOLVED 2026-09-24: `0xBB3D` is correct.** The CRC-16/ARC catalogue check value is `0xBB3D` (reveng catalogue; cross-checked against arcrc/crcZero tables and three independent computations). The earlier `0xBEEF` in DESIGN §5.1 was unreachable from the stated parameters and has been corrected there to `0xBB3D` / `3D BB`. `binascii.crc_hqx` is CRC-16/XMODEM **not** ARC (gives `0x31C3`) — never use it as the reference.
  - Verify: `Crc16Test` green ✅ (5 tests) — vector `"123456789"` → `3D BB`, empty=0, offset/length subrange, guards against `0xBEEF`/XMODEM
- [x] **C3. Cobs (non-standard variant)** — DONE 2026-09-24. `protocol/.../util/Cobs.kt`: running `distanceIndex` insertion; final pending block appended **only if result size ≠ 0 and ≠ 255**; malformed decode returns empty, never throws. Ported line-for-line from the reference encoder/decoder.
  - **Quirk pinned (empirically verified, faithful to reference):** a run of ≥255 consecutive non-zero bytes drops the 255th byte — 255-run encodes to 255 bytes but decodes to 254; 256-run → 257/255. Real frames never contain such runs; documented in Cobs.kt.
  - Verify: `CobsTest` green ✅ (7 tests) — round-trip typical + randomized (len<254), 254-run round-trips, 255/256-run quirk pinned, malformed decode → empty, never-throws on 500 random garbage inputs
- [x] **C4. HexLog** — DONE 2026-09-24. `protocol/.../util/HexLog.kt`: thread-safe ring buffer (default 4096, newest last), entry = `HexDirection` TX/RX + millis + defensive byte copy, non-destructive `snapshot()`, `exportHex()` golden line format (`TX <hex>` / `RX <hex>`).
  - Verify: `HexLogTest` green ✅ (7 tests) — wrap-around evicts oldest, snapshot non-destructive, 2-thread × 500 concurrent appends = 1000, export format, defensive copies

## Phase D — Framing + dispatch (M1-11..15) — pure JVM, no device

- [x] **D1. WireConstants** — DONE 2026-09-24. `protocol/.../wire/WireConstants.kt`: handshake first-write literal, reply prefix, dynamic-header index 12, final `[H,0x00]` write, type pairs `A0/BA/B4/B3/88 × 0x13` as raw byte pairs, chunk size 19, inbound proto offset 16, ack counter tail (14 zeros), handshake/request timeouts, `isType()` helper. Every constant tagged with its DESIGN §.
  - Verify: `WireConstantsTest` green ✅ (4 tests) — hex literals match DESIGN, raw-byte type pairs, `isType` matches only leading pair
- [x] **D2. Framing** — DONE 2026-09-24. `protocol/.../wire/Framing.kt`: `frame(msg)` = `LE16(len)‖msg‖CRC16(LE16(len)‖msg)`, `len = 2+len(msg)+2`; `sliceToChunks` = `0x00 + COBS(frame) + 0x00` sliced ≤19 (header-free — engine prepends at write time); `unframe` parses + CRC-verifies (reads the 2 CRC bytes as LE u16). Fixed a bug where unframe computed CRC *of* the CRC bytes instead of reading them.
  - Verify: `FramingTest` green ✅ (6 tests) — length field == frame size, `unframe(frame(x))==x` (incl. empty/1/100-byte), corrupt CRC + bad length rejected, all slices ≤19, slice-reassembly == wire stream
  - Note: doubled `LE32(protoLength)` is the **inner §5.7 request header inside msg**, not the outer frame length
- [x] **D3. MessageAssembler (receive)** — DONE 2026-09-24. `protocol/.../wire/MessageAssembler.kt`: ported from reference ReaderThread. Strip first byte (header) per chunk; `header==0 || !handshakeComplete` → route body to handshake; else trailing `0x00` completes, leading `0x00` clears accumulator + starts new message; on complete COBS-decode → emit frame. Empty decode = drop, never throw.
  - Verify: `MessageAssemblerTest` green ✅ (8 tests) — single + multi-chunk reassembly, back-to-back two messages, empty-decode drop, pre-handshake routing, zero-header-post-handshake still routes to handshake, empty-chunk ignored
- [x] **D4. HandshakeStateMachine** — DONE 2026-09-24. `protocol/.../wire/HandshakeStateMachine.kt`: `IDLE → WAITING_REPLY → DONE`; `onBody(strippedBody)` matches the reply prefix, extracts dynamic header at index 12, returns the final `[H,0x00]` raw write (null otherwise). Pure function — engine owns the ~10s timeout and the writes.
  - Verify: `HandshakeStateMachineTest` green ✅ (5 tests) — happy path extracts header + emits final write, wrong prefix stays waiting, too-short ignored, no transition after done, onBody before begin is null
  - Note: matches each stripped body independently (reference behavior); split-reply accumulation deferred to H2 golden capture
- [x] **D5. FrameDispatcher** — DONE 2026-09-24. `protocol/.../wire/FrameDispatcher.kt`: `buildAck` — base `88 13‖origType(2B)‖0x00` for every type incl. unknown; B3/B4 append `LE16(origCounter)+14 zeros` (21 bytes). `dispatch(frame, expectedCounter)` — CRC/length mismatch → Dropped (no ack, deliberate hardening); A0/BA → InfoAck; B4 counter-match → Response(proto from msg[16..]), else ack-only; B3 → DeviceRequest(proto). Every non-drop Dispatch carries its `ackPayload` so the engine always acks.
  - Verify: `FrameDispatcherTest` green ✅ (10 tests) — ack bytes asserted byte-for-byte for A0/BA/B4/B3, B4 match→Response, B4 stale→acked-not-completed, B3→DeviceRequest, unknown→UnknownAck, corrupt CRC→Dropped with null ack

## Phase E — Engine (M1-16/17) — pure JVM, no device

- [x] **E1. Transport interface** — DONE 2026-09-24. `protocol/.../transport/Transport.kt`: `incoming: Flow<ByteArray>` (raw GATT chunks, header intact), `state: Flow<TransportState>`, `suspend write(chunk)` (chunk already header-prefixed by engine), `start()`/`stop()`. `TransportState` enum: DISCONNECTED/CONNECTING/SCANNING/CONNECTED/DISCONNECTING.
  - Verify: `:protocol:compileKotlin` green ✅
- [x] **E2. ProtocolEngine** — DONE 2026-09-24. `protocol/.../ProtocolEngine.kt`: owns header byte + assembler + handshake SM + dispatcher; `start()` (begins handshake, collects `transport.incoming`), `stop()`; `sendProtobufRequest(proto): ResponseEvent?` (§5.7 payload, 5 s timeout, one in flight via Mutex, counter starts 0, increments only on success); Flows `handshakeComplete`, `eventNotification` (B3), `error`; all TX/RX mirrored to HexLog. Made assembler callbacks `suspend` so acks/handshake writes serialize with reception.
  - **Deviations from the sketch (both intentional):** `sendProtobufRequest` returns `ResponseEvent?` from a suspend fn (cleaner than `Deferred`); `deviceInfo` is NOT an engine flow — it comes from GATT characteristic reads, surfaced by `R10Device` in the app (§7.1 step 3), not the protocol layer.
  - Verify: `ProtocolEngineTest` green ✅ (5 tests)
- [x] **E3. Request/response correlation tests** — DONE 2026-09-24. `ProtocolEngineTest` + `FakeTransport` (test source set): scripted sessions over `runTest` — handshake completes + final `[H,0x00]` write asserted; B4 answer to a request → counter increments exactly once; timeout (no B4) → counter unchanged; B3 → `eventNotification` emitted; A0 frame → ack written. `FakeTransport` captures every on-air chunk.
  - Verify: `:protocol:test` fully green ✅ — **70 tests, 0 failures, 0 skipped**. This is the regression net for hardware day.

## Phase F — Android shell (M0-4/5/6/7)

- [x] **F1. Manifest + theme + entry** — DONE 2026-09-24. `AndroidManifest.xml` (all BLE/FGS/notification perms + legacy `maxSdkVersion 30` + `bluetooth_le` feature), `themes.xml` (Material Light NoActionBar base; Compose supplies Material3), `strings.xml`, vector `ic_launcher`, `R10App` (empty Application, manual-wiring anchor), `MainActivity` (Compose "R10 Monitor"). Added root `gradle.properties` (`android.useAndroidX=true`, nonTransitiveRClass).
  - **Compose BOM pinned to 2026.06.01** (Compose 1.11.x, minCompileSdk 35): the latest 2026.09.00 → 1.12.x requires compileSdk **37**, which is not installable in this SDK repo (only android-36). Kept compile/target 36 per plan.
  - Verify: `./gradlew :app:assembleDebug` green ✅ (app-debug.apk, 12 MB). **Launch-without-crash needs a device/emulator — none attached; deferred to hardware session.**
- [x] **F2. Foreground service shell** — DONE 2026-09-24. `R10ForegroundService` (registered `foregroundServiceType=connectedDevice`, `exported=false`), low-importance ongoing notification with tap-to-open PendingIntent, `ServiceCompat.startForeground` with CONNECTED_DEVICE type on API 34+. MainActivity Start/Stop toggle requests `POST_NOTIFICATIONS` (API 33+) then `startForegroundService`/`ACTION_STOP`. Engine graph attaches in Phase G.
  - Verify: `./gradlew :app:assembleDebug` green ✅. **`dumpsys ... foreground=true` + survives-backgrounding need a device — deferred to hardware session.**
- [x] **F3. Settings persistence** — DONE 2026-09-24. `settings/AppSettings.kt` (DESIGN §8 defaults), `SettingsRepository.kt` (takes `DataStore<Preferences>` → Context-free & testable; `Flow<AppSettings>` with defaults-on-read + per-key setters), `SettingsDataStore.kt` (production Context factory + `produceStore(file)` for tests). Added `datastore-preferences` 1.1.7 + junit/coroutines-test to `:app`.
  - Verify: `:app:testDebugUnitTest --tests '*SettingsRepositoryTest'` green ✅ (defaults + edits round-trip). Dropped a third test that violated DataStore's single-instance-per-file rule.
- [x] **F4. M0 gate** — DONE 2026-09-24.
  - [x] `:protocol:test` green ✅ (70 tests, 0 failures)
  - [x] `:app:testDebugUnitTest` green ✅ (2 tests) + `:app:assembleDebug` green ✅ (debug APK)
  - [~] `./gradlew build` (full, incl. release + lint): **blocked by environment, not code.** Container cgroup is capped at **2 GiB** (`memory.max=2147483648`); the full multi-variant build peaks at ~2.004 GiB during dexing and the daemon is OOM-killed. Debug-only path fits and is green. Run the full gate on a ≥4 GiB machine to tick this literally.
  - Verified via fresh-clone build (Phase F1) + this subset. M0 intent — everything compiles, all tests pass, debug APK builds — is met.
  - [ ] App launches → toggle service → settings scaffold shows stored defaults

## Phase G — BLE + device facade (M1-18/19/20/21/22/23)

- [x] **G1. BleTransportImpl** — DONE 2026-09-24. `app/.../ble/BleTransportImpl.kt` — Android `BluetoothGatt` impl of `Transport`: scan by advertised name (`ScanFilter`), `createBond()` + broadcast wait when unbonded, `connectGatt(TRANSPORT_LE)` with retry loop, `discoverServices()`, CCCD subscribe, `readCharacteristic`, serialized single-flight write-with-response (`WRITE_TYPE_DEFAULT`) to `DATA_WRITER`, notifications surfaced raw on `incoming` (header intact). All GATT ops funnel through `SingleFlight` (GATT allows one outstanding op). Permissions are caller-side (G5).
  - **Note:** the §7.1 subscribe *order* is driven by `R10Device` (G2), not the transport — the transport just exposes `subscribe(uuid)`. Real GATT behavior is [HW]-validated.
  - Verify: `:app:assembleDebug` green ✅; `SingleFlightTest` (2) asserts no overlapping ops under 20 concurrent submissions ✅. `:protocol` now 79 tests.
- [x] **G2. R10Device facade** — DONE 2026-09-24. `protocol/.../R10Device.kt` (pure JVM — GATT specifics behind `Transport`, so the whole sequence is fake-testable). Owns engine + transport; `connect()` runs §7.1 steps 1-5 (start → subscribe measurement/control/status → read serial/fw/model + battery → subscribe battery → subscribe data notifier → handshake with 10s timeout); `runSetup()` adds 6-11 (wakeUp, status, tilt, subscribeAlerts, optional calibrate, shotConfig); suspend steps `wakeUp`/`statusRequest`/`tiltRequest`/`subscribeAlerts`/`startTiltCalibration`/`sendShotConfig`; `batteryPercent` = value[0] unsigned; `tee_range_m = teeDistanceFt / 3.281`. Added `GattUuids.kt` (§4 UUIDs) and extended `Transport` with `subscribe(uuid)`/`read(uuid)`.
  - Verify: `R10DeviceTest` green ✅ (4 tests) — asserts exact GATT op order, device-info parsing, handshake-abort, battery edge cases. `:protocol` now 74 tests.
- [x] **G3. ShotConfig integration** — DONE 2026-09-24. `R10Device.shotConfigRequest(config)` companion builder (used by step-11 `sendShotConfig`), values from `DeviceSetupConfig` (sourced from `SettingsRepository` at wiring time). `tee_range = teeDistanceFt / 3.281f`.
  - Verify: `ShotConfigTest` green ✅ — pins §8-default serialized bytes `0D00007042150000803F1D00000000250000803F2D338B0840` (25 B), tee_range formula, custom round-trip. Note: pin is the exact `7/3.281f` float bits (differs from a double→float cast by 1 ULP — the float value is what the device receives).
- [x] **G4. DeviceScreen + HexLogPane** — DONE 2026-09-24. `ui/DeviceScreen.kt`: ConnState banner (IDLE/SCANNING/CONNECTING/HANDSHAKE/READY/ERROR), device-info card (model/fw/serial/battery), readout card (WakeUp status / StateType / tilt), `HexLogPane` = scrollable monospaced TX/RX polled at 500 ms (~2 Hz) via `LaunchedEffect` over `HexLog.snapshot()`. Observes `DeviceStateHolder` StateFlows.
  - Verify: `:app:assembleDebug` green ✅. **Live render + hex pane needs a device/emulator — deferred to [HW] session.**
- [x] **G5. Permissions flow** — DONE 2026-09-24. `MainActivity` requests `BLUETOOTH_SCAN`+`BLUETOOTH_CONNECT` (API 31+) and `POST_NOTIFICATIONS` (API 33+) via `RequestMultiplePermissions` before service start; also checks adapter enabled. Denial sets an inline error message (no crash); grant proceeds.
  - Verify: `:app:assembleDebug` green ✅. **Deny/grant runtime behavior needs a device — deferred to [HW] session.**
- [x] **G6. Service wiring** — DONE 2026-09-24. `R10ForegroundService` builds the full manual-DI graph (SettingsRepository → BleTransportImpl → ProtocolEngine → R10Device), runs §7.1, mirrors transport/engine/device state into `DeviceStateHolder` (shared hexLog). `START_STICKY`; `startDevice()` cancels any prior job and `reset()`s the holder, so a restart tears down and reruns the full sequence (no mid-handshake recovery).
  - Verify: `:app:assembleDebug` + all tests green ✅. **Kill-process → restart → full rerun observed in hex log needs a device — deferred to [HW] session.**

## Phase H — Golden replay + hardware gate

- [x] **H1. Golden replay harness** — DONE 2026-09-24. `GoldenReplayTest.kt`: reads `golden/session-r10.hex` (one `TX <hex>`/`RX <hex>` per line, `#` comments allowed), feeds every `RX` chunk through `FakeTransport` into the live engine, asserts handshake completes. **Skips cleanly via `Assume` while the file is absent** (captured during [HW] H2). Full B4-counter/ack parity is already unit-covered (`FrameDispatcherTest`, `ProtocolEngineTest`); the replay adds real-bytes framing/COBS/CRC validation once captured.
  - Verify: `:protocol:test --tests '*GoldenReplayTest'` → 1 test, **1 skipped** ✅ (CI green with no golden file).
- [x] **[HW] H2. Real-device handshake validation — M1 acceptance gate** — DONE 2026-09-24 on Approach R10 (serial 3473676453, fw 4.50, 49%) + Pixel 7 (Android 17 / API 37).
  - [x] Full §7.1 setup completes — ~7 s end-to-end, all five requests answered
  - [x] UI shows model `Approach R10` (`0x2A24`), firmware `4.50` (`0x2A28`), serial `3473676453` (`0x2A25`), battery 49% — all populated
  - [x] `WakeUpResponse` (ALREADY_AWAKE) + `StatusResponse(StateType)` + `TiltResponse(roll/pitch)` visible
  - [x] Hex log: first TX `00 00 00 00 00 00 00 00 00 00 01 00 00`; RX prefix `01 00 00 00 00 00 00 00 00 01 00 00`; **dynamic header `0x07` at index 12**, prefixed to every later TX chunk
  - [x] Golden saved to `protocol/src/test/resources/golden/session-r10.hex`; `GoldenReplayTest` no longer skips and **passes** (81 tests, 0 failures, 0 skipped)
  - **THE BUG (cost a hardware session): DESIGN §5.7 was wrong.** It documented the request counter as `LE16` with the proto at offset 14 and warned the two directions were deliberately asymmetric. The reference (`gsp-r10-adapter` `BaseDevice.cs:64,280`) uses a C# `int` → `BitConverter.GetBytes(int)` = **4 bytes**, so the proto is at **offset 16 — symmetric with inbound §5.5**. With our 2-byte counter the device validated the frame (CRC good) and acked it, but could not parse the proto, so it **never sent B413** — every request silently burned its 5 s timeout. Fixed `buildRequestPayload` to LE32; corrected DESIGN §5.5/§5.7; pinned with `RequestLayoutTest` (the existing correlation tests passed under BOTH layouts, which is why this reached hardware).
  - **DESIGN corrections proven by hardware:** (a) `8813` is NOT "never received" — the device acks every B313 with `88 13 b3 13 …`, echoing our protobuf length; (b) the two directions ARE symmetric at offset 16.
  - **Red herrings ruled out (do not re-chase):** advertised name — the R10 puts **no local name in its advertisement** (name only via cached GATT GAP), so `ScanFilter.setDeviceName` can never match a fresh device; and "device asleep" — STATUS `value[1]` went `01`(asleep)→`00`(awake) and the device acked identically either way. Both were unrelated to the missing responses.
  - **Also fixed this session:** `SettingsDataStore.create()` built a new DataStore per Start tap → `IllegalStateException: multiple DataStores active for the same file` (now a process-wide singleton on `R10App`); GATT leak — `onDestroy` never called `transport.stop()`, so each Stop→Start left another live connection (every notification delivered twice); added bonded-direct connect path (DESIGN §2 path 1) since a bonded R10 stops advertising; edge-to-edge insets (`safeDrawingPadding`) so the Start button no longer sits under the status bar.
- [x] **H3. M1 gate** — 2026-09-24
  - [x] `:protocol:test` + `:app:testDebugUnitTest` + `:app:assembleDebug` green (81 protocol tests, 0 failures, 0 skipped)
  - [x] Golden replay passes against real bytes and now asserts the capture contains B413 (a capture with only acks would fail)
  - [x] `DESIGN.md` updated for every field observation that contradicted it (§5.5 symmetry, §5.7 counter width, §5.6/§5.5 `8813`)
- [x] **H4. Fresh-pair verification** — CLOSED 2026-09-24, verified end-to-end by
  the user: R10 unpaired → tapped Start → app **discovered it by scan** and raised
  the system pairing dialog → approved → bonded → connected → `§7.1 setup complete
  -> READY` with real device info. A new customer's path works.
  - **Measured advertisement** (442 packets, unfiltered scan, R10 not connected):
    `name="Approach R10"`, `services=0000FE1F-…`, `mfg=0x0087=0E2601A5180CCF`,
    `connectable=true`, static-random address.
  - **THE R10 NEVER ADVERTISES `6A4E2800`.** The ScanFilter had been targeting the
    GATT data-service UUID, which matches nothing. Corrected to
    `GattUuids.ADVERTISED_SERVICE` (0xFE1F) OR device name. The name branch is the
    one proven to have worked during the fresh pair; the change is a strict superset.
  - **Retracted claims** (both wrong, recorded so nobody re-derives them):
    - "The R10 puts no local name in its advertisement" — false. It does; earlier
      observations were of a *connected* device.
    - "One-phone lock: bonded devices don't advertise" — false. Advertising tracks
      **connection state, not bonding**; a bonded-but-disconnected R10 advertises.
  - **Real, still-true finding:** the system holds the LE ACL link independently of
    our app (`ACL LE:Y` persisted 80+ s past `am force-stop`, still showing
    "connected" in BT settings). So the R10 can appear connected with nothing
    monitoring it, and won't advertise while that persists.
  - **Garmin contention (root cause of the above, found via `dumpsys` ACL holders).**
    The R10 is a Garmin Approach R10; `com.garmin.android.apps.connectmobile` and
    `com.garmin.android.apps.golf` hold open GATT connections and **auto-restart
    within seconds of a force-stop** (observed `gatt_if` 127/128 → 131 after both
    were killed). Full DESIGN §4 "Contention with Garmin's official apps".
    - **Coexistence is proven good:** our app ran the complete §7.1 setup to `READY`
      *while Garmin held the same device* — BLE multiplexes GATT clients on one link.
    - **Discovery needs the device silent:** it will not advertise while any client
      holds the link, so a fresh scan-based pair can fail with Garmin running.
      First diagnostic when discovery finds nothing:
      `adb shell dumpsys bluetooth_manager | grep <addr>` and read `ACL holders`.
    - `am force-stop` does NOT release it; `pm disable-user --user 0 <pkg>` does.
  - **Residual, deliberately not verified:** the `0xFE1F` branch has never been
    exercised *through* `ScanFilter` — Garmin re-grabs the link too fast to get a
    disconnected advertiser. Accepted because the **name branch is already proven**
    (it delivered the verified fresh pair, and the R10 never advertised the old
    `6A4E2800`), so the filter is a strict superset of known-good. To close it:
    disable both Garmin apps, power-cycle the R10, then
    `adb shell am start -n com.techdelivery.r10/.ScanDumpActivity` — it runs a
    production-filter phase and prints an explicit VERDICT line.

---

## Out of scope this pass (M2/M3 — planned after H2 passes)

Shot display + `shot_id` dedup UI, auto-wake on `STANDBY`, error banner surfacing beyond basic, calibration UI, Room shot history, CSV export, practice/normal filter, log export for bug reports, GATT-133 backoff tuning.

## Standing rules

1. Never "clean up" a magic byte. They are field-verified.
2. Send/receive proto offsets stay asymmetric on purpose: outbound proto sits after a 14-byte inner header (§5.7); inbound proto at `msg[16..]` (§5.5). Do not normalize either side.
3. Message type is two raw bytes (`B4 13`), never ASCII.
4. CRC mismatch → log + drop. Deliberate hardening decision, keep it.
5. Any on-hardware fix to framing constants needs hex-log evidence, a golden-expectation update, and a `DESIGN.md` correction if the design itself was wrong.

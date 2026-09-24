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
- [ ] **D2. Framing** — `frame(msg)` = `LE16(len) || msg || CRC16(...)` with `len = 2 + len(msg) + 2`; `S = 0x00 + COBS(frame) + 0x00`; slice ≤19. Header-free (engine adds header at write time).
  - Verify: `./gradlew :protocol:test --tests '*FramingTest'` green; all slices ≤ 19; `unframe(frame(x)) == x`
  - Note: doubled `LE32(protoLength)` is the **inner §5.7 request header inside msg**, not the outer frame length
- [ ] **D3. MessageAssembler (receive)** — strip first byte of every chunk; leading stripped `0x00` clears accumulator and starts new message; trailing `0x00` completes → COBS-decode → verify CRC → emit msg (proto region = `msg[16..]`); corrupt CRC / empty decode = **log + drop, never throw** (§5.5 deliberate).
  - Verify: `./gradlew :protocol:test --tests '*MessageAssemblerTest'` green incl. split-mid-message, back-to-back messages, CRC-corrupt drop
- [ ] **D4. HandshakeStateMachine** — `Idle → WaitingReply → GotHeader(h) → Done`; reply matched on stripped-body prefix, dynamic header at index 12; emits `[h, 0x00]` raw write on GotHeader; 10 s no-match → failure event.
  - Verify: `./gradlew :protocol:test --tests '*HandshakeStateMachineTest'` green incl. timeout path + reply split across chunks
- [ ] **D5. FrameDispatcher** — ack for **every** received frame incl. unknown types: base `88 13 || origType(2B) || 0x00`; B3/B4 append `LE16(origCounter) + 14 zeros` (21 bytes total); route B4 → response channel by counter match, B3 → device-request event, A0/BA → ack-then-ignore.
  - Verify: `./gradlew :protocol:test --tests '*FrameDispatcherTest'` green, ack bytes asserted byte-for-byte for all 5 cases

## Phase E — Engine (M1-16/17) — pure JVM, no device

- [ ] **E1. Transport interface** — `write(bytes)`, `incoming: Flow<ByteArray>`, `state: Flow<TransportState>`.
  - Verify: `./gradlew :protocol:compileKotlin` green
- [ ] **E2. ProtocolEngine** — owns header byte, assembler, handshake SM, dispatcher; API `start()`, `stop()`, `sendProtobufRequest(proto): Deferred<ResponseEvent>` (5 s timeout, one in flight, counter starts 0 per connection, increment only on success); Flows: `handshakeComplete`, `deviceInfo`, `eventNotification`, `error`; all TX/RX through HexLog.
  - Verify: `./gradlew :protocol:test --tests '*ProtocolEngineTest'` green
- [ ] **E3. Request/response correlation tests** — scripted `FakeTransport` session: handshake + B4 answer to StatusRequest. Assert counter increments exactly once, ack bytes match §5.6 for every frame type seen, timeout leaves counter unchanged.
  - Verify: `./gradlew :protocol:test` fully green — this is the regression net for hardware day

## Phase F — Android shell (M0-4/5/6/7)

- [ ] **F1. Manifest + theme + entry** — `BLUETOOTH_SCAN(neverForLocation)`, `BLUETOOTH_CONNECT`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`; legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` maxSdkVersion 30; Material3 theme; `MainActivity` + `R10App`.
  - Verify: `./gradlew :app:assembleDebug` green; installs + launches to "R10 Monitor" without crash
- [ ] **F2. Foreground service shell** — `R10ForegroundService` typed `connectedDevice`, ongoing notification, start/stop toggle in skeleton UI.
  - Verify: `adb shell dumpsys activity services com.techdelivery.r10` shows `foreground=true` while toggled on; survives backgrounding
- [ ] **F3. Settings persistence** — DataStore `AppSettings` per DESIGN §8 defaults: autoWake true, calibrateTiltOnConnect false, temperature 60, humidity 1, altitude 0, airDensity 1, teeDistanceFt 7, debugLogging false, reconnectIntervalS 5, deviceName "Approach R10". `Flow<AppSettings>`.
  - Verify: `./gradlew :app:testDebugUnitTest --tests '*SettingsRepositoryTest'` green; defaults round-trip
- [ ] **F4. M0 gate**
  - [ ] `./gradlew build` green on clean checkout
  - [ ] `:protocol:test` green
  - [ ] App launches → toggle service → settings scaffold shows stored defaults

## Phase G — BLE + device facade (M1-18/19/20/21/22/23)

- [ ] **G1. BleTransportImpl** — scan (name filter from settings), `createBond()` flow when unbonded, connect retry loop, service discovery per DESIGN §4, CCCD subscribe in exact §7.1 order (measurement → control-point → status; then device-info reads; then battery READ+subscribe; then data-channel notifier last), serialized single-flight writes-with-response ≤20 bytes, notifications surfaced raw (header byte intact — stripping is the engine's job).
  - Verify: `./gradlew :app:assembleDebug` green; write queue unit test asserts no overlapping writes
- [ ] **G2. R10Device facade** — owns engine + transport; suspend steps `wakeUp`, `statusRequest`, `tiltRequest`, `subscribeAlerts`, `startTiltCalibration`, `sendShotConfig`; `connect()` runs full §7.1 order; battery value[0]=%; `tee_range_m = teeDistanceFt / 3.281`.
  - Verify: `./gradlew :app:assembleDebug` green; sequence order asserted by a fake-transport facade test
- [ ] **G3. ShotConfig integration** — sent last in setup (step 11), values from `SettingsRepository`.
  - Verify: `:protocol` test pins serialized `ShotConfigRequest` bytes for §8 defaults
- [ ] **G4. DeviceScreen + HexLogPane** — connection state (idle/scanning/connecting/handshake/ready/error), model/fw/serial/battery block, WakeUp status + StateType + tilt readouts, scrollable monospaced hex pane polled ~2 Hz while visible.
  - Verify: renders on device/emulator; hex pane shows live TX/RX
- [ ] **G5. Permissions flow** — runtime `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` (+ `POST_NOTIFICATIONS`) requested before service start; denial explained inline.
  - Verify: deny → clear inline message, no crash; grant → scan proceeds
- [ ] **G6. Service wiring** — service builds `R10Device` (manual DI), UI observes state Flow, `START_STICKY` restart resumes from scratch (teardown + full rerun, no mid-handshake recovery).
  - Verify: kill app process → service restarts → full §7.1 rerun observed in hex log

## Phase H — Golden replay + hardware gate

- [ ] **H1. Golden replay harness** — `protocol/src/test/resources/golden/session-r10.hex`, one event per line (`TX <hex>` / `RX <hex>`); replay feeds RX lines through `FakeTransport` into engine; asserts handshake completes, each B4 matches its request counter, no ack byte deviates from §5.6.
  - Verify: test **skips cleanly** while golden file absent (CI stays green)
- [ ] **[HW] H2. Real-device handshake validation — M1 acceptance gate** — *Owner: user. Assistant fixes from pasted hex.*
  - [ ] R10 in pairing mode → full §7.1 setup completes with ≤1 manual retry
  - [ ] UI shows model `0x2A24`, firmware `0x2A28`, serial `0x2A25`, battery %
  - [ ] `WakeUpResponse` + `StatusResponse(StateType)` + `TiltResponse(roll/pitch)` visible
  - [ ] Hex log shows TX `[00 00 00 00 00 00 00 00 00 00 01 00 00]`, RX body prefix `010000000000000000010000`, dynamic header at index 12 then prefixed to every later TX chunk
  - [ ] Golden file saved; `GoldenReplayTest` no longer skips and passes
- [ ] **H3. M1 gate**
  - [ ] `./gradlew build` + all `:protocol` tests green on clean checkout
  - [ ] Second hardware run after any fix shows no regression vs previous golden
  - [ ] `DESIGN.md` updated for any field observation that contradicted it; plan kept in sync only where DESIGN changed

---

## Out of scope this pass (M2/M3 — planned after H2 passes)

Shot display + `shot_id` dedup UI, auto-wake on `STANDBY`, error banner surfacing beyond basic, calibration UI, Room shot history, CSV export, practice/normal filter, log export for bug reports, GATT-133 backoff tuning.

## Standing rules

1. Never "clean up" a magic byte. They are field-verified.
2. Send/receive proto offsets stay asymmetric on purpose: outbound proto sits after a 14-byte inner header (§5.7); inbound proto at `msg[16..]` (§5.5). Do not normalize either side.
3. Message type is two raw bytes (`B4 13`), never ASCII.
4. CRC mismatch → log + drop. Deliberate hardening decision, keep it.
5. Any on-hardware fix to framing constants needs hex-log evidence, a golden-expectation update, and a `DESIGN.md` correction if the design itself was wrong.

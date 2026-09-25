# R10 Monitor — Android Design Document

A standalone Android application that connects to a Garmin Approach R10 launch
monitor over Bluetooth LE, runs the device's setup sequence, receives shot
metrics, and displays them live on the phone.

This document is the single source of truth: it captures everything needed to
build the app from a blank repository, including the complete wire protocol
(reverse-engineered from observed device traffic). All byte-level behavior in
§5 is field-verified — implement it exactly and never "clean up" the magic
bytes. No networking functionality is included.

---

## 1. Scope & Goals

**In scope**

- BLE connection to a paired Garmin Approach R10
- Full device setup sequence (wake, status, tilt, alert subscription, shot config)
- Receiving and parsing shot metrics (ball / club / swing)
- Live shot display, shot history, device state and battery display
- Debug logging of raw BLE traffic for troubleshooting

**Non-functional goals**

- Survives screen-off and app backgrounding (foreground service)
- Automatic reconnection after BLE drops or device standby
- Shot data deduplicated by the device's shot id

---

## 2. Platform & Tech Stack

| Choice | Value | Rationale |
|---|---|---|
| Language | Kotlin | Coroutines/Flow map well to the async BLE stream |
| Min SDK | 26 (Android 8.0) | Modern BLE APIs; broad device coverage |
| Target SDK | 34+ | Foreground service type `connectedDevice` required |
| UI | Jetpack Compose | Single-activity, state-driven UI |
| Protobuf | `com.google.protobuf` Gradle plugin + `protobuf-javalite` | Compile the R10 `.proto` schema |
| Persistence | Room | Shot history |
| Settings | DataStore (Preferences) | App settings |
| DI | Hilt (optional; plain singletons acceptable) | Keep it simple |
| Tests | JUnit + kotlinx-coroutines-test | Protocol framing must be unit-tested |

**Permissions**

- `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_CONNECT` (runtime, API 31+)
- `BLUETOOTH` / `BLUETOOTH_ADMIN` with max SDK 30 (for older devices)
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`
- `ACCESS_FINE_LOCATION` only for pre-API-31 scanning

**Pairing strategy (API 31+):** the app offers both paths —
1. Use an already-bonded device (paired via system Settings), or
2. Request bonding in-app via `BluetoothDevice.createBond()` after discovery.

Bonding is required: the R10 will not communicate over an unencrypted GATT link.

---

## 3. Architecture

```
┌─────────────────────────────────────────────────────┐
│  UI (Compose)                                       │
│  - Connection/device screen (state, battery, tilt)  │
│  - Shot table (ball / club / swing)                 │
│  - Settings screen                                  │
│  - Debug log pane (raw hex in/out)                  │
└───────────────▲─────────────────────────────────────┘
                │ StateFlow / SharedFlow
┌───────────────┴─────────────────────────────────────┐
│  R10ForegroundService (foreground, connectedDevice) │
│  - Owns R10Device lifecycle                         │
│  - Exposes app-wide singleton state                 │
└───────────────▲─────────────────────────────────────┘
                │
┌───────────────┴─────────────────────────────────────┐
│  R10Device                                          │
│  - Setup sequence, command/request API              │
│  - Shot events as Flow<Metrics>                     │
│  - State, tilt, battery, error events               │
└───────────────▲─────────────────────────────────────┘
                │ frames (byte[] in/out)
┌───────────────┴─────────────────────────────────────┐
│  ProtocolEngine                                     │
│  - Handshake state machine, framing, COBS, CRC16    │
│  - Message dispatch (B313/B413), acks (8813)        │
│  - Request/response correlation with counter        │
└───────────────▲─────────────────────────────────────┘
                │ encoded chunks
┌───────────────┴─────────────────────────────────────┐
│  BleTransport (Android BluetoothGatt wrapper)       │
│  - Connect / discover / subscribe / read / write    │
│  - Callbacks → Channel<BlePacket>                   │
│  - Reconnect logic, GATT error codes                │
└─────────────────────────────────────────────────────┘
```

**Concurrency model:** one of the following constructs per stream, using
coroutines + `Channel`s:

- `bleIn: Channel<ByteArray>` — notification payloads from GATT callbacks
- `frameOut: Channel<ByteArray>` — messages to write
- One consumer coroutine each for: frame decoding (reassembly → COBS decode →
  CRC check → dispatch), message processing (proto parse → events), writing
  (queue → chunk → GATT write with `WRITE_TYPE_DEFAULT`, i.e. with response,
  serialized — Android allows only one outstanding GATT write).

---

## 4. GATT Layout

Services/characteristics on the R10 (write with response unless noted):

### BLE advertisement (measured 2026-09-24 from a real unit)

Captured with a debug-only unfiltered scan (`ScanDumpActivity`), 442 packets from
an R10 that was **not connected**:

```
address   CE:33:1E:DF:1D:07        (static random — stable across sessions)
name      "Approach R10"           (present in the advertisement)
services  0000FE1F-0000-1000-8000-00805f9b34fb   (16-bit 0xFE1F)
mfg data  0x0087 = 0E2601A5180CCF
connectable true
```

**The R10 never advertises `6A4E2800-…`** (the GATT data service below). Discovery
must filter on `0xFE1F` and/or the name — filtering on the data-service UUID
matches nothing.

**Advertising is a function of connection state, not bonding.** A bonded-but-
disconnected R10 advertises normally; a *connected* one does not advertise at all.
The system can also hold the LE ACL link after our app is gone (`ACL LE:Y` observed
80+ s past a force-stop), so "our app isn't monitoring" does not imply "the R10 is
advertising."

### Contention with Garmin's official apps (important)

The R10 is a **Garmin Approach R10**, and Garmin's own apps contend for it:
`com.garmin.android.apps.connectmobile` and `com.garmin.android.apps.golf` hold
open GATT connections and **auto-restart within seconds of a force-stop**, re-
grabbing the link immediately (observed: `gatt_if` 127/128 → 131 after both apps
were force-stopped).

**Coexistence works.** BLE multiplexes multiple GATT clients onto one ACL link. Our
app completed the full §7.1 setup — handshake, all five requests, `READY` — while
Garmin held the same device. We do not need Garmin out of the way to function.

**Discovery does need the device silent.** The R10 stops advertising while *any*
client holds the link. So with Garmin installed and running, a fresh scan-based
pair can fail — not because the filter is wrong, but because the device is already
connected and not broadcasting. If fresh discovery ever mysteriously finds nothing,
check `dumpsys bluetooth_manager` for `GATT_CH_OPEN ... ACL holders` on the R10
address before suspecting our code.

**Why BT settings shows "connected" but only offers "Connect":** the settings
toggle manages *profile* (BR/EDR) connections. A raw LE ACL link held by another
app's GATT client is not a profile, so there is nothing for that toggle to
disconnect. Use `dumpsys bluetooth_manager` to identify the holder, and
`pm disable-user` (not `am force-stop`) to actually take it away.

### Device interface service (the data channel)
| UUID | Role |
|---|---|
| `6A4E2800-667B-11E3-949A-0800200C9A66` | Service |
| `6A4E2812-667B-11E3-949A-0800200C9A66` | Notifier — subscribe (CCCD 0x0001) |
| `6A4E2822-667B-11E3-949A-0800200C9A66` | Writer — all app→device writes go here |

### Launch monitor service (measurement/control/status)
| UUID | Role |
|---|---|
| `6A4E3400-667B-11E3-949A-0800200C9A66` | Service |
| `6A4E3401-667B-11E3-949A-0800200C9A66` | Measurement — subscribe (post-shot bytes; not parsed) |
| `6A4E3402-667B-11E3-949A-0800200C9A66` | Control point — subscribe (unused) |
| `6A4E3403-667B-11E3-949A-0800200C9A66` | Status — subscribe (byte 1 = awake flag, byte 2 = ready flag; debug-only) |

### Standard services
| UUID | Role |
|---|---|
| `0000180f-0000-1000-8000-00805f9b34fb` | Battery service |
| `00002a19-0000-1000-8000-00805f9b34fb` | Battery level (read + notify) |
| `0000180a-0000-1000-8000-00805f9b34fb` | Device Information |
| `00002a24-…` Model name, `00002a25-…` Serial, `00002a28-…` Firmware (read, ASCII) |

---

## 5. Wire Protocol

The framing/encoding details below were reverse-engineered from observed
device traffic. Implement them exactly — do not "clean up" the magic bytes;
they are undocumented device behavior.

### 5.1 Encoding primitives

**COBS** (Consistent Overhead Byte Stuffing) — this is a *non-standard variant*;
port the exact behavior below, do not substitute textbook COBS:
- Encoder keeps a running `distanceIndex` marking where the last distance byte
  was placed; each new distance byte is inserted at that position (shifting the
  accumulated payload bytes right). After the input is consumed, the pending
  final block is appended **only if its length is non-zero and ≠ 255**.
- Decoder walks blocks by their leading distance byte; malformed input
  (distance pointing past end of buffer) returns empty. Callers treat an empty
  decode as a dropped frame with a log line — never throw.
- Regression-test boundaries: all-zero inputs, and runs of exactly 254 / 255 /
  256 consecutive non-zero bytes.

**CRC16** — polynomial `0xA001` reflected, init `0x0000`, table-driven lookup
(standard CRC-16/ARC parameters), result emitted little-endian (low byte first).
Frozen test vector: ASCII `"123456789"` → `0xBB3D`, written as bytes `3D BB`
(CRC-16/ARC catalogue check value, cross-verified against independent
implementations). Note: `binascii.crc_hqx` is CRC-16/XMODEM, **not** ARC —
never use it as the reference.

### 5.2 Frame format (app → device)

1. **Payload** `P` = message bytes (e.g. `B313` message below)
2. **Length**: `len = 2 + len(P) + 2` (uint16, little-endian) — payload length
   plus the length and CRC fields themselves
3. **Framed** = `LE16(len) || P || CRC16(LE16(len) || P)`
4. **COBS-encode** the framed bytes
5. **Delimit**: prepend `0x00` and append `0x00` → wire stream S =
   `0x00 + COBS(frame) + 0x00`
6. **Chunk**: split S into slices of ≤19 bytes; **prefix every slice with the
   current header byte H** (initially `0x00`, the dynamic value after handshake).
   Every GATT write is therefore ≤20 bytes — safe at default ATT MTU 23.
7. Write the slices in order to the writer characteristic, each with response,
   strictly serialized (one outstanding write). The receiver strips the first
   byte of every received chunk, so reassembly reconstructs S exactly (§5.3).

### 5.3 Device → app notifications

Notification payloads arrive as *chunks* of one logical message:

- Each chunk's **first byte is the header byte** — strip it.
- Before handshake completes, all chunks feed the handshake state machine.
- After handshake:
  - If chunk's **last byte == 0x00**: message complete; strip that trailing 0x00.
  - If chunk's first remaining byte == 0x00: this starts a new message; clear
    the accumulator first, then skip that byte.
- Accumulate chunk bodies until complete, then COBS-decode the accumulated
  bytes → framed message.

### 5.4 Handshake (performed after data-channel subscribe)

1. **First write — a single raw (unframed, un-COBS'd, no CRC) GATT write of
   13 bytes**: current header byte (`0x00` pre-handshake) + the 12 literal
   bytes from hex `000000000000000000010000`, i.e. `[H, 0×9, 0x01, 0x00, 0x00]`.
2. **Device reply**: one or more notification chunks; strip each chunk's
   leading header byte per the §5.3 rule. The handshake is recognized when a
   stripped body starts with the 12-byte hex prefix `010000000000000000010000`
   (observed in field traffic it arrives as one chunk — if a device ever splits
   the reply across chunks, accumulate pre-handshake bodies before matching).
3. **Dynamic header byte** = byte at index 12 of that stripped body — i.e.
   immediately after the 12-byte prefix. Save it; from now on every outgoing
   chunk (raw or framed) is prefixed with this value instead of `0x00`.
4. App sends one more raw write: `[H, 0x00]` (2 bytes), then marks handshake
   complete and switches the inbound pipeline to message reassembly (§5.3).
5. If no matching reply arrives within ~10 s, treat the handshake as failed:
   abort setup, tear down GATT, surface the error.

### 5.5 Framed message dispatch (post-handshake, decoded)

Decoded message = `LE16(len) || msg || CRC16(LE16(len) || msg)`.
Verify CRC over everything except the last 2 bytes against the last 2 bytes;
on mismatch, log and drop.

`msg` structure:

| Offset in msg | Content |
|---|---|
| 0–1 | Message type — **two raw bytes**: `A0 13`, `BA 13`, `B4 13`, `B3 13`. ("B413" is hex notation for that byte pair, not ASCII characters.) |
| 2–3 | uint16 LE counter |
| 4–15 | reserved / unparsed — typically zero on device-originated frames; do **not** assume any content here |
| 16– | protobuf `WrapperProto` payload (B413/B313 only) — proto always starts at offset 16 of msg |

Dispatch on type:

- **`A013`** — device info (not parsed; acked).
- **`BA13`** — config (not parsed; acked).
- **`B413`** — protobuf **response**. Ack it. If `counter == current request
  counter`, parse `WrapperProto` from `msg[16..]`, complete the pending
  request, increment the counter.
- **`B313`** — protobuf **request** (device → app; e.g. event notifications).
  Ack it, parse `WrapperProto` from `msg[16..]`, emit events, handle.
- **`8813`** — the app's ack format. **Correction 2026-09-24:** this document
  previously said "never received"; the device **does** send `8813` frames —
  it acks every app `B313` request with `88 13 b3 13 …`, echoing the request's
  protobuf length. Ack them with the base body like any unrecognized type.

**Offset symmetry (corrected 2026-09-24, hardware-verified)**: our B313 requests
carry a **16-byte** inner header before the proto, and device-originated B4/B3
frames also carry the proto at **offset 16** of msg (§5.5). The two directions
**are** symmetric. An earlier revision of this document claimed a 14-byte
request header and warned not to normalize the asymmetry — that was wrong and
cost a hardware session: with a 2-byte counter the device validates the frame
(CRC good) and acks it, but cannot parse the proto, so it never answers with
B413. Pinned by `RequestLayoutTest`.

### 5.6 Acknowledgements

Every received frame gets an app acknowledgement — `A013`, `BA13`, `B413`,
`B313`, and even unrecognized types (base body only). The ack payload `P` is:

- **Base (all types)**: `88 13 || origType(2 bytes) || 0x00`
  — e.g. for a received B413 the base body is `88 13 B4 13 00`. (5 bytes.)
- **B413 / B313 only**: append after the base body
  `LE16(origCounter)` + 14 zero bytes → full payload 21 bytes, where
  `origCounter` is the uint16 read from msg[2..4] of the frame being acked.

The ack travels through the normal framing path (§5.2: length/CRC/COBS/chunks)
with the current dynamic header byte prefixing each chunk.

### 5.7 App → device protobuf request format

`requestCounter` starts at **0** on every connection.

Payload `P` for a protobuf request:

```
B3 13                        // type — two raw bytes, not ASCII
|| LE32(requestCounter)     // 4 bytes — the reference counter is a C# `int`,
                           // so BitConverter.GetBytes(int) emits FOUR bytes
|| 0x00 0x00                // 2 bytes
|| LE32(protobufLength)     // 4 bytes (BitConverter LE32)
|| LE32(protobufLength)     // 4 bytes again
|| protobufBytes            // starts at offset 16
```

Send through the framing path (§5.2). Wait up to 5 s for the matching `B413`
response (same counter); only then increment `requestCounter` — a timeout
leaves the counter unchanged for the next attempt. One request in flight at a
time (the response event gates the next).

**Offset note (corrected 2026-09-24)**: the proto payload starts **16 bytes**
into P — the same offset inbound device frames use (§5.5). This document
previously stated LE16 + "14 bytes"; that is incorrect. The counter is a C#
`int` in the reference (`BaseDevice.cs:64,280`), so `BitConverter.GetBytes`
yields four bytes. A 2-byte counter shifts the proto to offset 14, the device
acks the frame but never responds — a silent failure. Regression-pinned by
`RequestLayoutTest`.

**Inbound counter width differs from outbound**: inbound B413/B313 read the
counter as `UInt16` from `msg[2..4]` (§5.5), while outbound writes it as LE32
across `msg[2..6)`. Harmless for counters < 65536, since the LE32 low half
equals the LE16 value and the high half is zero.

### 5.8 Raw (non-framed) writes

Only the two handshake writes (§5.4) bypass framing/CRC/COBS entirely.
Post-handshake, every message goes through the §5.2 framing path. All GATT
writes — raw or framed — carry the current header byte as their first octet;
post-handshake that byte is always the dynamic value obtained in §5.4 step 3.

---

## 6. Protobuf Messages

Schema is `LaunchMonitor.proto` (copied verbatim into the new repo, package
`LaunchMonitor.Proto`). Full text in Appendix A.

Envelope: `WrapperProto { EventSharing event = 30; LaunchMonitorService service = 38; }`

- **Requests (app → device)** via `LaunchMonitorService`:
  `StatusRequest` (1), `WakeUpRequest` (3), `TiltRequest` (5),
  `StartTiltCalibrationRequest` (7), `ResetTiltCalibrationRequest` (9, field
  `should_reset`), `ShotConfigRequest` (11: temperature, humidity, altitude,
  air_density, tee_range — all floats).
- **Responses** (same message numbers +1): status state, wake status, tilt,
  calibration status, shot config success.
- **Alert subscription** via `EventSharing.subscribe_request` with alert type
  `LAUNCH_MONITOR` (8); device answers with `subscribe_respose` and later sends
  `EventSharing.notification` → `AlertNotification{ type, AlertDetails = 1001 }`.
- **`AlertDetails`** carries `State`, `Metrics`, `Error`, `CalibrationStatus`.
  Shot data lives in `Metrics { shot_id, shot_type, BallMetrics, ClubMetrics,
  SwingMetrics }`.

### Units in the protobuf
Speeds are **m/s**, angles in degrees, spin in rpm, `tee_range` in **meters**.

---

## 7. Device Behavior

### 7.1 Setup sequence (on connect, in order)

Device must be powered on / in pairing mode (blue blinking light — hold the
power button a few seconds). Any mid-setup failure or link drop tears down the
GATT client and restarts from step 1 (no partial recovery); retries use
`reconnectIntervalS`.

1. Connect GATT, with a retry loop until connected.
2. Subscribe to measurement, control-point, and status characteristics
   (CCCD each) — these come **first**, before any reads or the handshake.
3. Read device info as ASCII: serial (`0x2A25`), firmware (`0x2A28`), model
   (`0x2A24`). Battery: one initial READ, then subscribe to notifications;
   level = value byte 0 (%) — the initial read is an app-side convenience,
   notify-only also works.
4. Subscribe to the device-interface notifier (CCCD) — data channel open.
5. **Handshake** (§5.4). Failure aborts setup.
6. `WakeUpRequest` (`LaunchMonitorService.wake_up_request`) — safe when
   already awake (response reports `ALREADY_AWAKE`).
7. `StatusRequest()` → current `StateType` (UI "ready" = `WAITING`).
8. `TiltRequest()` → device tilt (roll/pitch).
9. Subscribe to alerts: `EventSharing.subscribe_request` with
   `alerts = [{type: LAUNCH_MONITOR}]`; the device answers via a normal B413
   response containing `subscribe_respose`, and from then on pushes
   `EventSharing.notification` frames as B313 messages.
10. If configured (`calibrateTiltOnConnect`): `StartTiltCalibrationRequest`.
11. Send `ShotConfigRequest` with settings: temperature (°F, default 60),
    humidity (default 1), altitude (m, default 0), air density (default 1),
    tee_range = teeDistanceFt (default 7) ÷ 3.281 meters.

### 7.2 Runtime events

- **State changes** via alert `State`: `STANDBY`(asleep), `WAITING`(ready),
  `RECORDING`, `PROCESSING`, `ERROR`, `INTERFERENCE_TEST`.
  - On `STANDBY`: if auto-wake enabled, send `WakeUpRequest`; otherwise
    notify user.
- **Shots** via alert `Metrics`: dedup by `shot_id` (keep a processed-id set;
  duplicate = ignore).
- **Errors** via alert `Error`: codes `OVERHEATING`, `RADAR_SATURATION`,
  `PLATFORM_TILTED` with severity warning/serious/fatal → surface in UI.
- **Tilt calibration** completion → re-read tilt.
- Post-shot measurement-characteristic bytes exist but are unparsable — ignore.

### 7.3 Metric conversion (for display)

Conversion functions:

```
MPH = m/s × 2.2369

BallData:
  ballSpeed   = BallMetrics.ball_speed × 2.2369      (mph)
  launchAngle = launch_angle                          (deg)
  launchDir   = launch_direction                       (deg, HLA)
  spinAxis    = spin_axis × −1                         (deg)
  totalSpin   = total_spin                             (rpm)
  sideSpin    = totalSpin × sin(−1 × spin_axis × π/180)
  backSpin    = totalSpin × cos(−1 × spin_axis × π/180)

ClubData:
  clubSpeed   = club_head_speed × 2.2369               (mph)
  faceAngle   = club_angle_face                        (deg)
  path        = club_angle_path                        (deg)
  attackAngle = attack_angle                           (deg)

Swing timing (microseconds):
  backswingStart, downswingStart, impactTime, followThroughEnd,
  endRecording → tempo = backswingDuration / downswingDuration
```

---

## 8. Data Model & Persistence

Room entities:

- **Shot**: id (autogen), deviceShotId (dedup key component — NOT unique on its
  own; see the implementation note below),
  timestamp, shotType (practice/normal), ballSpeedMph, launchAngle,
  launchDirection, spinAxisDeg, totalSpin, sideSpin, backSpin, clubSpeedMph,
  faceAngle, path, attackAngle, rawMetrics (proto bytes, optional, for future
  re-parsing).
- **Setting keys** (DataStore): deviceName (default "Approach R10"), autoWake
  (default true), calibrateTiltOnConnect (default false), temperature (60),
  humidity (1), altitude (0), airDensity (1), teeDistanceFt (7), debugLogging
  (false), reconnectIntervalS (5).

**Persistence implementation note (2026-09-25).** The shipped store is an
append-only CSV (`filesDir/shots.csv`), not Room. Same logical `Shot` fields as
above, plus the derived display columns and `raw_metrics_hex`. Reasons: KSP has no
release matching the pinned Kotlin 2.4.x compiler, and routing Room through kapt
was rejected because the build container is capped at 2 GiB and the extra
annotation-processing round tips the build over. The persisted shape is flat,
numeric and app-owned, so CSV is lossless here and doubles as the CSV export with
no second serializer. Replacing `ShotCsvStore` with a Room DAO is a drop-in change:
nothing else reads the file. Dedup across sessions must keep the same guarantee the
`deviceShotId` unique index was meant to give — the in-memory dedup is per
connection only, so a Room migration should enforce the unique key on insert.

**Dedup key resolution (2026-09-25).** `deviceShotId` cannot be that key as
written. The R10 restarts its `shot_id` sequence on every power cycle, so a
global unique constraint on `shot_id` would reject legitimate new shots after a
reboot. The stable identity of a re-pushed shot is its bytes, so `ShotCsvStore`
deduplicates on `shot_id || hex(raw_metrics)` over the most recent 2000 rows.
A Room migration should use the same composite key, not `deviceShotId` alone.
Shots with no `raw_metrics` carry no key and are always written.

---

## 9. UI Design

Single activity, three tabs:

1. **Device** — connection state machine visualization (disconnected →
   connecting → handshake → ready), model/firmware/serial/battery, current
   state chip (WAITING/RECORDING/…), tilt, error banners, reconnect button,
   pairing flow for unbonded devices.
2. **Shots** — most recent shot detail card (big numbers: ball speed, carry-
   relevant metrics, spin axis visual) + scrolling table of all shots with
   ball/club/swing columns. Filter practice/normal.
3. **Settings** — all settings keys above; debug logging toggle that reveals a
   hex log pane (raw chunk in, framed, decoded, proto message lines).

Foreground notification: persistent, shows connection state + battery;
tapping opens the app.

---

## 10. Milestones

**M0 — Skeleton**
Blank repo scaffold: Gradle + protobuf plugin, Compose app, settings screen,
foreground service scaffold, permissions. App runs, does nothing BLE yet.

**M1 — BLE transport bring-up (hardest)**
- `BleTransport`: scan/bond/connect/retry, service discovery, CCCD subscribe,
  serialized writes with response, GATT callback → Channel plumbing.
- Implement `Cobs`, `Crc16`, byte helpers exactly per §5.1; freeze test
  vectors using an independent reference at implementation time.
- `ProtocolEngine`: handshake, framing, chunking, dispatch, acks, request/
  response correlation. Unit-test framing round-trips.
- Debug hex log pane from day one.
- **Test**: complete handshake on real hardware; read model/firmware/serial/
  battery; successful StatusRequest + TiltRequest.

**M2 — Shot data**
- Setup sequence (wake/subscribe/shot config/optional calibration); state
  machine, auto-wake, error surfacing; metric conversion + dedup.
- **Test**: hit balls, shots appear correctly in-app with sane units.

**M3 — Polish**
- Robust reconnect (GATT 133 handling, retry backoff, clear-cache fallback).
- Room shot history, CSV export, practice/normal filter, notification updates,
  log export for bug reports.

---

## 11. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Undocumented framing quirks (dynamic header, magic bytes, acks) | Port exactly; never deviate; validate with real device + hex logs at M1 before proceeding |
| Android GATT 133 errors / flaky stacks | Retry with backoff, refresh-services workaround, full reconnect cycle; foreground service mandatory |
| Out-of-order GATT writes corrupt stream | Strictly serialize writes (single writer coroutine, await each callback) |
| Background BLE killed by OS | Foreground service with `connectedDevice` type; test on Android 12–15 |
| CRC/COBS edge cases (255-byte blocks, zero bytes) | Exhaustive unit tests against captured device traffic |
| Bonding required & may fail silently | Detect bond state, guide user through pairing in-app, clear-bond retry |
| One request in-flight assumption | Enforce with mutex/gate around SendProtobufRequest |

---

## Appendix A — LaunchMonitor.proto

Complete schema, matching the firmware's proto exactly — transcribe verbatim.
Implementation note: `AlertNotification` field 1001 is named after its parent
message; codegen produces an accessor with a trailing underscore (e.g.
JavaLite/Kotlin `alertNotification_`). Use generated names as-is.

Codegen note: the app's copy adds `option java_outer_classname = "R10Protos";`
because the proto package's first segment (`LaunchMonitor`) collides with the
file-derived outer class name, which breaks Kotlin-lite fully-qualified
references (the class shadows the package segment). This is codegen-only —
serialized bytes are unaffected.

```
syntax = "proto3";

package LaunchMonitor.Proto;

message WrapperProto {
  optional EventSharing event = 30;
  optional LaunchMonitorService service = 38;
}

message LaunchMonitorService {
  optional StatusRequest status_request = 1;
  optional StatusResponse status_response = 2;
  optional WakeUpRequest wake_up_request = 3;
  optional WakeUpResponse wake_up_response = 4;
  optional TiltRequest tilt_request = 5;
  optional TiltResponse tilt_response = 6;
  optional StartTiltCalibrationRequest start_tilt_cal_request = 7;
  optional StartTiltCalibrationResponse start_tilt_cal_response = 8;
  optional ResetTiltCalibrationRequest reset_tilt_cal_request = 9;
  optional ResetTiltCalibrationResponse reset_tilt_cal_response = 10;
  optional ShotConfigRequest shot_config_request = 11;
  optional ShotConfigResponse shot_config_response = 12;
}

message StatusRequest {}
message StatusResponse {
  optional State state = 1;
}
message WakeUpRequest {}
message WakeUpResponse {
  optional ResponseStatus status = 1;
  enum ResponseStatus {
    SUCCESS = 0;
    ALREADY_AWAKE = 1;
    UNKNOWN_ERROR = 2;
  }
}
message TiltRequest {}
message TiltResponse {
  optional Tilt tilt = 1;
}
message StartTiltCalibrationRequest {}
message StartTiltCalibrationResponse {
  optional CalibrationStatus status = 1;
  enum CalibrationStatus {
    STARTED = 0;
    IN_PROGRESS = 1;
    ERROR = 2;
  }
}
message ResetTiltCalibrationRequest {
  optional bool should_reset = 1;
}
message ResetTiltCalibrationResponse {
  optional Status status = 1;
  enum Status {
    UNKNOWN = 0;
    CAN_RESET = 1;
    ALREADY_RESET = 2;
    RESET_SUCCESSFUL = 3;
    CANNOT_RESET = 4;
  }
}
message ShotConfigRequest {
  optional float temperature = 1;
  optional float humidity = 2;
  optional float altitude = 3;
  optional float air_density = 4;
  optional float tee_range = 5;
}
message ShotConfigResponse {
  optional bool success = 1;
}

message EventSharing {
  optional SubscribeRequest subscribe_request = 1;
  optional SubscribeResponse subscribe_respose = 2;
  optional AlertNotification notification = 3;
  optional AlertSupportRequest support_request = 4;
  optional AlertSupportResponse support_response = 5;
}

message SubscribeRequest {
  repeated AlertMessage alerts = 1;
}

message SubscribeResponse {
  repeated AlertStatusMessage alert_status = 1;

  message AlertStatusMessage {
    optional Status subscribe_status = 1;
    optional AlertMessage type = 2;

    enum Status {
      SUCCESS = 0;
      FAIL = 1;
    }
  }
}

message AlertSupportRequest {

}
message AlertSupportResponse {
  repeated AlertNotification.AlertType supported_alerts = 1;
  optional uint32 version_number = 2;
}

message AlertMessage {
  optional AlertNotification.AlertType type = 1;
  optional uint32 interval = 2;
}

message AlertNotification {
  optional AlertType type = 1;

  optional AlertDetails AlertNotification = 1001;

  enum AlertType {
    ACTIVITY_START = 0;
    ACTIVITY_STOP = 1;
    LAUNCH_MONITOR = 8;
  }

}

message AlertDetails {
  optional State state = 1;
  optional Metrics metrics = 2;
  optional Error error = 3;
  optional CalibrationStatus tilt_calibration = 4;
}

message State {
  optional StateType state = 1;

  enum StateType {
    STANDBY = 0;
    INTERFERENCE_TEST = 1;
    WAITING = 2;
    RECORDING = 3;
    PROCESSING = 4;
    ERROR = 5;
  }
}

message CalibrationStatus {
  optional StatusType status = 1;
  optional CalibrationResult result = 2;

  enum StatusType {
    UNKNOWN = 0;
    IN_BOUNDS = 1;
    RECALIBRATION_SUGGESTED = 2;
    RECALIBRATION_REQUIRED = 3;
  }

  enum CalibrationResult {
    SUCCESS = 0;
    ERROR = 1;
    UNIT_MOVING = 2;
  }
}

message Error {
  optional ErrorCode code = 1;
  optional Severity severity = 2;
  optional Tilt deviceTilt = 3;

  enum ErrorCode {
    UNKNOWN = 0;
    OVERHEATING = 1;
    RADAR_SATURATION = 2;
    PLATFORM_TILTED = 3;
  }

  enum Severity {
    WARNING = 0;
    SERIOUS = 1;
    FATAL = 2;
  }
}

message Tilt {
  optional float roll = 1;
  optional float pitch = 2;
}

message Metrics {
  optional uint32 shot_id = 1;
  optional ShotType shot_type = 2;
  optional BallMetrics ball_metrics = 3;
  optional ClubMetrics club_metrics = 4;
  optional SwingMetrics swing_metrics = 5;

  enum ShotType {
    PRACTICE = 0;
    NORMAL = 1;
  }
}

message BallMetrics {
  optional float launch_angle = 1;
  optional float launch_direction = 2;
  optional float ball_speed = 3;
  optional float spin_axis = 4;
  optional float total_spin = 5;
  optional SpinCalculationType spin_calculation_type = 6;
  optional GolfBallType golf_ball_type = 7;

  enum SpinCalculationType {
    RATIO = 0;
    BALL_FLIGHT = 1;
    OTHER = 2;
    MEASURED = 3;
  }

  enum GolfBallType {
    UNKNOWN = 0;
    CONVENTIONAL = 1;
    MARKED = 2;
  }
}

message ClubMetrics {
  optional float club_head_speed = 1;
  optional float club_angle_face = 2;
  optional float club_angle_path = 3;
  optional float attack_angle = 4;
}

message SwingMetrics {
  optional uint32 back_swing_start_time = 1;
  optional uint32 down_swing_start_time = 2;
  optional uint32 impact_time = 3;
  optional uint32 follow_through_end_time = 4;
  optional uint32 end_recording_time = 5;
}
```

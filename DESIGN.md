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
| Persistence | protobuf records in `shots.bin` (R7; CSV is a derived export) | Shot history, losslessly |
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
address   <MAC>        (static random — stable across sessions)
name      "Approach R10"           (present in the advertisement)
services  0000FE1F-0000-1000-8000-00805f9b34fb   (16-bit 0xFE1F)
mfg data  0x0087 = 0E2601A5180CCF
connectable true
```

**The R10 never advertises `6A4E2800-…`** (the GATT data service below). Discovery
must filter on `0xFE1F` and/or the name — filtering on the data-service UUID
matches nothing.

**Advertising is a function of connection state, not bonding — but the PAYLOAD is a
function of bonding.** A bonded-but-disconnected R10 does advertise (168 packets
observed in 20 s), so "bonded devices go silent" is false. What changes is *what*
it advertises. Measured 2026-09-25 on the same unit, now **paired** and
**disconnected**, 75 s continuous scan, exactly ONE distinct payload:

```
02 01 04              Flags (LE only, no BR/EDR)
05 FF 87 00 0E 26     Manufacturer Specific: company 0x0087 (Garmin), 2 data bytes
00 00 00 ...          zero padding to 62 bytes
```

There is **no `0x09`/`0x08` local-name field and no service UUID in the packet at
all.** The `"Approach R10"` string that scan callbacks report comes from the
phone's cached GATT name (`BluetoothDevice.getName()`), not from the
advertisement — which is why it looks present while being unmatchable.

Consequences, both load-bearing:

- `ScanFilter.setDeviceName("Approach R10")` **cannot match a paired R10** — the
  name is not in the advertisement, and hardware filtering matches the packet, not
  the cache.
- `ScanFilter.setServiceUuid(0xFE1F)` **cannot match a paired R10** either — that
  UUID is absent from this payload.
- Therefore the **bonded-direct connect path (§2 path 1) is not an optimization, it
  is the only way to reach a paired R10.** A scan-only implementation would be
  permanently unable to reconnect after the first pair.

The `0xFE1F` + name + 7-byte-mfg form above was captured from an **unpaired** unit,
so the discoverable form appears to belong to pairing mode. Still to be proven
end-to-end (see TODO K3): Forget the R10 → power-cycle → the `0xFE1F` form should
return and the production filter should match it.

The system can also hold the LE ACL link after our app is gone (`ACL LE:Y` observed
80+ s past a force-stop), so "our app isn't monitoring" does not imply "the R10 is
advertising."

**Android gotcha that produced a false "0 devices found":** an **unfiltered** scan
is refused while the screen is off — `BtScan.ScanManager: Cannot start unfiltered
scan in screen-off` — and silently delivers nothing (no exception, zero results).
Wake the screen first (`adb shell input keyevent KEYCODE_WAKEUP && adb shell svc
power stayon true`). Filtered scans are unaffected, so this only bites
diagnostics, never the production path.

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

Shot history is **not** stored in Room — the log is the protobuf record file
described under "Persistence" below, and the settings are DataStore. What the
shot record effectively holds (there is no table, so this is the shape, not a
schema):

- **Shot** (one `StoredShot` record): the device's `Metrics` message, kept
  verbatim, plus the two facts only this app knows — arrival time, and the club
  label the user picked. `deviceShotId` (from those metrics) is part of the dedup
  key — NOT identity on its own; see the implementation note below. Every
  displayed value is recomputed from the stored bytes on load, so no float ever
  passes through text and `rawMetrics` is re-parsable by construction.
- **Setting keys** (DataStore): deviceName (default "Approach R10"), autoWake
  (default true), calibrateTiltOnConnect (default false), temperature (60),
  humidity (1), altitude (0), airDensity (1), teeDistanceFt (7), debugLogging
  (false), reconnectIntervalS (5).

**App settings (ROADMAP R5, built):** `ownedClubs` (the set of clubs the user
owns, drawn from the canonical club list) and `currentClub` (the last-picked club,
used to stamp arriving shots). Neither is exported with the CSV.

**Persistence: protobuf records, CSV as a derived export (ROADMAP R7, 2026-09-27).**
The store is **not** Room and **not** a CSV file. It is the R10's own protobuf:
`filesDir/shots.bin` holds length-delimited records (protobuf's own stream framing —
a varint length per record, which is what `parseDelimitedFrom` reads), opening with a
`ShotLogHeader` record carrying `store_format_version`, then one `StoredShot` per
shot. `StoredShot` is an **app-owned** message, not part of the device protocol: it
wraps the untouched `Metrics` message plus the only two facts the app owns —
`received_at_ms` and the user-picked `club_label`.

Three consequences, all of them the point:

- **Lossless by construction.** The device's bytes are stored verbatim, and every
  displayed value is recomputed on load by `MetricConverter` — the same conversion
  that ran when the shot arrived. No float ever passes through text, so there is no
  formatting rule that can lose precision, and no torn row that can drop a field.
- **No schema machinery.** Protobuf's `optional` already means "unset", so a field
  added later reads as absent. The **store** carries no version column: what R7
  removed is the CSV's store-side `migrate()`, which protobuf does not need.
  `store_format_version` remains in the header for the rare *destructive* change,
  so a future reader can refuse a file it cannot interpret instead of guessing.
  The **export** keeps `schema_version`, `HEADER_V1` and the per-version row
  checks, so a CSV this app wrote earlier still loads and still validates clean.
- **Damage is local.** A record that cannot be framed is reported with its index and
  skipped; reading stops there, because past an unreadable length prefix the offsets
  are no longer trustworthy. A torn tail costs the tail, not the session. A
  *rewrite* refuses to run on a damaged file for the same reason — it would write
  back only the readable records, deleting the rest — and the export reports the
  damage instead of claiming a clean copy. A torn tail is *repaired* before an
  append, not refused: appending past a file that does not end on a record boundary
  writes a shot where the reader never looks (never loaded, never exported, never
  validated, with every later shot stranded behind the same tear while `append`
  still reported success). The append rewrites the intact prefix first — byte for
  byte, through the same atomic path — and drops only the bytes past the tear.
  Refusing instead would leave a store that has lost a tail permanently unable to
  record a shot.

Framing was chosen over a single `repeated` message (also pure proto) because a
`repeated` log must be rewritten for every new shot — giving up the one-fsync append
that makes a shot durable in a single write — and because one bad byte would make
the whole file unparseable instead of costing the records around it.

**CSV is now an export, not a store.** `ShotCsvFormat` still encodes, decodes and
validates the same 23-column layout with the same R4 rules, because a readable file
is the deliverable; it is produced on the way out by `ShotProtoStore.exportCsv` and
validated by the Export button. History is not migrated from the old CSV — clearing
storage was available, so the cut is clean rather than carrying a back-compat layer
for a file the user chose to discard.

One CSV-only rule survives the cut, because it is about the export rather than
about the store: `schema_version` still leads the row, `HEADER_V1` is still
accepted, and the per-version row checks are still applied per version — so both
headers are accepted, a v1 CSV the user still has loads and validates clean, and a
row carrying a version this build does not know is reported by line rather than
guessed at (`ShotCsvFormatTest`). The store has no `migrate()`; the format's
`decode` still reads a row's version from its shape alone, and never rewrites a
file on the way in.

**Why not Room, restated (2026-09-26).** KSP has no release matching the pinned
Kotlin 2.4.x compiler, and the kapt route was rejected on memory grounds that depend
on the host rather than on the project (see "Memory-restricted workspaces" below).
The persisted shape is flat,
numeric and app-owned, so the store is lossless here and the CSV export is produced
from it with no second source of truth. Replacing `ShotProtoStore` with a Room DAO
is a drop-in change: nothing else reads the file. Dedup across sessions must keep the same guarantee the
`deviceShotId` unique index was meant to give — the in-memory dedup is per
connection only, so a Room migration should enforce the unique key on insert.

**Memory-restricted workspaces (2026-09-26).** The original Room rejection cited
a hard 2 GiB container cap, which made the build's memory budget look like a fixed
property of the project. It is not: the budget comes from `gradle.properties`
(`org.gradle.jvmargs`) and from whatever limit the host or CI runner imposes, so
the same checkout can build on a large workstation and fail on a small one. Treat
Room as a deliberate choice, not a blocked one, and decide it deliberately:

- If the host is memory-constrained (container, CI runner, laptop), check the
  budget before blaming the toolchain: raise `org.gradle.jvmargs` if the host
  allows it, disable the Gradle daemon and configure-on-demand for one-off
  builds, and only then consider dropping to kapt or adding KSP.
- If the budget is fixed and genuinely too small, say so explicitly here, with the
  measured failure (heap OOM during which task), so the next reader does not
  re-derive it on a machine that has plenty of memory.
- Either way, do not let this drift: the store holds the device's protobuf bytes, which
  are lossless by construction, and the CSV export is produced from it rather than
  persisted, so there is no forcing function pulling toward Room. Revisit it as an
  explicit decision, not as a side effect of upgrading the toolchain.

**CSV validation (2026-09-26, ROADMAP R4).** The export is no longer "copy the file
and report the byte count". `ShotCsvFormat.validateText` checks the header
against `COL_NAMES`, then every row: column count, integer and numeric fields,
`shot_type` as a known enum name, and `raw_metrics_hex` even-length and hex-only.
Problems are reported per line (`line 42: …`), capped at 5 with a remainder count,
and the Export button shows the summary. This matters because `decode`
deliberately returns null for a malformed row — without an explicit pass, a torn
file exports as a success with rows silently missing.

**The store is mutable, but only from inside it (decided 2026-09-26, ROADMAP R5;
restated for the protobuf store in R7).** Tagging a shot with a club means editing
a record that already exists, and a shot can be deleted outright, so the store is
not append-only and is not going to be. The rule that keeps this from drifting
half-mutable: `ShotProtoStore` stays the only writer of `shots.bin`, under its
mutex, and the UI never touches the file. Its operations are:

- `append(shot)` / `appendAll(shots)` — one durable append per write.
- `updateClub(shotId, receivedAtMs, club)` — rewrite the file with that one
  record changed. The pair is the record's identity, not the id alone: the R10
  restarts its `shot_id` sequence on every power cycle, and the UI already keys
  rows on the pair.
- `deleteShot(shotId, receivedAtMs)` — rewrite the file without that record
  (same pair, same reason). Needed
  independently of the club tag: a mis-hit practice swing, a record from a bad
  session, or a shot the user simply does not want in their history. Deleting is a
  user-visible data loss, so the UI must confirm it and there is no undo — a CSV
  export the user already took is the only way back.
- `exportCsv(dir, stamp)` — write a derived, human-readable copy.
- `validate()` — header present, every record a header or a shot.
- `clear()` — delete everything.

**Where the code lives (as built).** Five units, split by job rather than by file
size: `ShotProtoStore` owns the file, the mutex and the durability rules;
`ShotRecordCodec` translates a `Shot` to and from its `StoredShot` record;
`DelimitedRecords` owns the length-delimited framing; `ShotDedupIndex` owns the
bounded LRU of stored-payload keys; `ShotCsvFormat` owns the export's encoding and
validation and touches no file. The split exists so the store stays about
durability, the format about compatibility, and a rewrite can be reasoned about
without holding a lock in your head.

**One writer: the queue owns ordering (decided 2026-09-28, ROADMAP parked item A).**
`ShotProtoStore`'s mutex *serialises* its callers; it does not *order* them, and
that is the whole question when a device push and a user edit race. `ShotWriteQueue`
is the answer: a process-wide singleton on `R10App` and the only thing in the app
that writes `shots.bin`. Three rules make it correct, and each exists because
breaking it produced a real failure:

- **Every write is a `ShotWriteOp`** — `Append`, `SetClub`, `DeleteShot` — and they
  run in submission order. It is a queue over *all* of them, not over appends:
  when the queue fronted `append` only and the UI called the store directly, a
  delete could be undone by an append still sitting in the queue, and a club pick
  on a shot not yet on disk matched nothing, was discarded, and was never retried.
  Both are now impossible, and both have a test.
- **Nothing reaches the UI before it is submitted.** The service calls `submit`
  *then* `addShot`, so "the user can see it" implies "it is queued or written".
  That is what lets an edit aimed at a visible shot find its row.
- **The two doors differ on purpose.** `submit` is non-blocking, because a
  blocking shot collector would backpressure `R10Device.shots`, then
  `ProtocolEngine._events`, then the BLE inbound reader; a full append queue drops
  the shot, counts it and says so. `apply` suspends and returns what happened,
  because the user is already waiting and the UI must not mirror a change into the
  live list before it knows the file changed.

**A refusal says which refusal it is.** `updateClub` and `deleteShot` return a
`WriteOutcome`, not a `Boolean`: `NOT_FOUND` (a no-op — the shot is not there) and
`DAMAGED` (the store refused a rewrite that would drop records past a tear) were
both `false`, so the UI had to re-read and re-validate the whole file after every
refused edit just to decide whether to apologise. The writer is holding the store
when it finds out, so it reports the difference. Only `DAMAGED` and `REJECTED` are
worth an error banner.

**The queue is never closed by the service.** It is app-lifetime, not
connection-lifetime: the shot history outlives any one connection, and a queue
closed on Stop could only promise a bounded drain that might time out — losing
exactly the rows it was meant to protect. The service stops feeding it and
nothing else.

`append` is the cheap path: the framed bytes are appended and fsynced, so a shot is
durable in one write. `updateClub` and `deleteShot` are rewrites — read all records,
apply the change, write a temp file and rename it over the original, so a process
kill mid-write leaves the previous file intact rather than a truncated history.
Editing one record in an append-only log is the price of the format. A rewrite also
rebuilds the in-memory dedup index from the surviving records, because the index is
a cache of what the file contains — deleting a record must not leave a key that
suppresses a re-pushed shot later. `clear()` keeps its own faster path.

**What one read may cost (decided 2026-09-28, ROADMAP parked item C).** A read
looks at at most `ShotProtoStore.MAX_FILE_BYTES` (32 MiB), checked against the
file's length *before* anything is allocated, and a file over the ceiling is read
only as far as it. This replaces a `MAX_RECORDS` cap that bounded nothing: it was
applied to records that had already been materialised, out of a buffer that was
already the whole file, and its KDoc credited itself with stopping "a corrupt
length" from allocating forever — which is not what it did, and not what stopped
that either. That is `DelimitedRecords.read`'s bounds check, which compares every
length against the bytes actually remaining, and a test now pins the two apart.

The ceiling is in **bytes** because bytes are what a read costs. A record is ~90
bytes — the store keeps the device's own `Metrics` rather than derived text, so a
shot costs almost nothing — which puts 32 MiB at roughly 385 000 shots, about 25
years of a hundred-shot session three times a week. It is a ceiling, not a target;
nothing real comes close, and neither did the old cap.

A rewrite **cannot** be bounded this way: it writes back every record, so past the
ceiling `updateClub` and `deleteShot` refuse (`DAMAGED`) rather than truncate, and
the file is left byte-identical. Silently dropping the oldest shots is not an
acceptable version of "bounded". The one place this could have gone badly wrong is
`repairTailUnlocked`, which fixes a torn tail by writing back the bytes the walk
accounted for: past the ceiling those are only the bytes that were read, so a
repair would have deleted everything past it — and a repair is on the *append*
path, so the loss would have arrived with the user's next shot. A file over the
ceiling is therefore never repaired, and there is a test for exactly that.

Truncation is **loud**. `loadAll()` returns the shots together with what reading
them could not cover, and the Shots tab puts the problems on the existing error
banner, so an over-large file says so instead of looking like a history that
quietly stops mid-session. Shots appended past the ceiling are kept rather than
dropped — refusing the append would lose a real shot silently — but they land
outside the readable window, which is the honest cost of a file this far past the
ceiling and something only `clear()` fixes.

**What a rewrite is allowed to lose (decided 2026-09-28, ROADMAP parked item B).**
A rewrite is refused only when the file holds bytes the reader never saw: a torn
tail, or a file past the read ceiling above. A record that frames correctly but is
neither a header nor a shot is **carried through the rewrite verbatim** instead,
because its bytes are in hand and both rewrites already write such a record back
untouched. `RecordScan.rewriteSafe` says so in positive terms for exactly this
reason — the
store used to answer "is this file damaged?", which conflated *cannot read these
bytes* with *does not understand these bytes*, and those two have opposite
consequences. Getting it wrong was not subtle: one uninterpretable record made the
file permanently un-mutable, so the user could neither tag nor delete any shot,
forever, and the only way out was `clear()`. Preserving such a record also happens
to be the right call for a *forward-compatible* format — a shot record written by a
newer `store_format_version` lands in exactly this case. It is still not *accepted*:
`validate()` names it, and it is invisible to the shots the UI loads.

**Club ownership is app settings, not shot data (ROADMAP R5, built).** The set
of clubs the user owns lives in `AppSettings` (DataStore) and is deliberately not
exported: the export carries the device's measurements plus the one annotation
attached to a shot, while a bag inventory is personal setup, not part of a shot
record.

**Dedup key resolution (2026-09-25).** `deviceShotId` cannot be that key as
written. The R10 restarts its `shot_id` sequence on every power cycle, so a
global unique constraint on `shot_id` would reject legitimate new shots after a
reboot. The stable identity of a re-pushed shot is its bytes, so `ShotProtoStore`
deduplicates on `shot_id || hex(raw_metrics)` over the most recent 2000 records.
A Room migration should use the same composite key, not `deviceShotId` alone.
Shots with no `raw_metrics` carry no key and are always written.

---

## 9. UI Design

Single activity, three tabs:

1. **Device** — connection state machine visualization (disconnected →
   connecting → handshake → ready), model/firmware/serial/battery, current
   state chip (WAITING/RECORDING/…), tilt, error banners, reconnect button,
   pairing flow for unbonded devices.
2. **Shots** — a level banner (see below), a detail card for the **selected** shot
   (big numbers: ball speed, carry-relevant metrics, spin axis visual) + scrolling
   table of all shots with ball/club/swing columns. Filter practice/normal. Tap a
   row to select it; the card follows the selection and falls back to the newest
   shot when nothing is selected, so a new shot never steals a deliberate
   selection. Selection is marked by a `▶` marker plus bold text and a heavier
   border, not by colour alone, so it survives a colour-blind palette or a
   sunlight-washed screen.
   - **Level banner (ROADMAP R2).** The R10 refuses to record while it is not
     level, which used to be visible only on the Device tab — so a session
     produced nothing and looked like a dead app. The banner is triggered by the
     device's own `PLATFORM_TILTED` error, not by an app-invented threshold: the
     unit knows its own tolerance, and a locally-guessed one can disagree with it
     and be worse than nothing. Live pitch/roll is shown alongside, from a typed
     `TiltReading` in `DeviceStateHolder` (a formatted string is fine for display
     and useless for logic). When the device is level, the same numbers render as
     a quiet one-line readout rather than a banner.
   - **Club tag (ROADMAP R5).** The device supplies club *metrics*, never
     the club *identity*, so the club is a user annotation. The picker hangs off
     the selected shot, offers the clubs the user owns in Settings, and the
     last-picked club stamps each arriving shot so a session does not need a tap
     per ball. The tag is always visible and always correctable. See §8 for the
     store and schema rules behind it.
3. **Settings** — all settings keys above; debug logging toggle that reveals a
   hex log pane (raw chunk in, framed, decoded, proto message lines).
   - **"Clubs I own" (ROADMAP R5).** Checkbox grid over the canonical club
     list (driver → putter), multi-select, stored in `AppSettings` and not
     exported. It filters the Shots-tab club picker, which falls back to the full
     list when the owned set is empty.

**Screen-awake policy (ROADMAP R1).** The Shots tab sets `keepScreenOn` on the
window: the screen sleeping mid-session loses the shot you just hit. Every other
tab releases it, so the phone can still sleep in the pocket. The window flag only
applies while the window is focused, so backgrounding the app does not pin the
screen either.

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
- Shot history (the protobuf record store, R7), CSV export, practice/normal
  filter, notification updates, log export for bug reports.

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

The device schema, matching the firmware's proto exactly — transcribed verbatim.
Everything above the app-owned block at the end is firmware; that last block is
**not**: `ShotLogHeader` and `StoredShot` were added in ROADMAP R7 and the R10
never sends either. They live in the same file only because the codegen is shared
(§8).
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

// ---------------------------------------------------------------------------
// App-owned persistence records (ROADMAP R7). NOT part of the R10 protocol: the
// device never sends these, and they are field-compatible with nothing else.
// Listed here so this appendix stays an honest picture of the file the app
// actually compiles.
// ---------------------------------------------------------------------------

// First record of shots.bin. A header record, rather than a flag or a file name,
// so a reader can tell what it is holding before it trusts a single shot.
message ShotLogHeader {
  optional uint32 store_format_version = 1;
}

// One shot, as the app stores it: what the device sent (metrics), plus the two
// facts only this app knows — when the shot arrived, and which club the user hit.
message StoredShot {
  optional int64 received_at_ms = 1;
  optional string club_label = 2;
  optional Metrics metrics = 3;
}
```

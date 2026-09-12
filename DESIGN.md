# R10 Monitor — Android Design Document

A standalone Android application that connects to a Garmin Approach R10 launch
monitor over Bluetooth LE, runs the device's setup sequence, receives shot
metrics, and displays them live on the phone.

This document is self-contained: it captures everything needed to build the app
from a blank repository, including the full wire protocol reverse-engineered
from the desktop adapter this design was derived from. No networking functionality is included.

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
1. Use an already-bonded device (pair via system Settings, like the desktop app), or
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
| `6A4E3403-667B-11E3-949A-0800200C9A66` | Status — subscribe (awake/ready raw bytes) |

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

**COBS** (Consistent Overhead Byte Stuffing) — standard algorithm, with these
implementation details:
- Encoder: insert distance byte at position `distanceIndex`; final block
  appended only if `result.Count != 255 && result.Count > 0`.
- Decoder: returns empty list on malformed input (distance out of range).

**CRC16** — polynomial `0xA001` (standard "Modbus"/CRC-16-IBM), init `0x0000`,
bitwise table-driven, result little-endian (2 bytes).

### 5.2 Frame format (app → device)

1. **Payload** `P` = message bytes (e.g. `B313` message below)
2. **Length**: `len = 2 + len(P) + 2` (uint16, little-endian) — payload length
   plus the length and CRC fields themselves
3. **Framed** = `LE16(len) || P || CRC16(LE16(len) || P)`
4. **COBS-encode** the framed bytes
5. **Delimit**: prepend `0x00` and append `0x00`
6. **Chunk**: write in ≤19-byte chunks (prefix nothing per chunk; the 0x00
   header byte is part of the first chunk). Do not negotiate MTU — keep the
   19-byte chunking.
7. Write each chunk to the writer characteristic with response.

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

### 5.4 Handshake

1. App sends `SendBytes("000000000000000000010000")` — i.e. those 12 raw bytes,
   each **prefixed with the current header byte** (header starts as `0x00`,
   so effectively one 13-byte write). Note: these are *raw* chunk payloads,
   not COBS-framed.
2. Device replies with a chunk whose body (hex) starts with
   `010000000000000000010000`.
3. The **dynamic header byte** = the 13th byte of that body (index 12).
   Save it; it prefixes every subsequent raw write.
4. App sends `SendBytes("00")` (single byte, header-prefixed) and marks the
   handshake complete.

### 5.5 Framed message dispatch (post-handshake, decoded)

Decoded message = `LE16(len) || msg || CRC16(LE16(len) || msg)`.
Verify CRC over everything except the last 2 bytes against the last 2 bytes;
on mismatch, log and drop.

`msg` structure (relative to the length field):

| Offset | Content |
|---|---|
| 0–1 | Message type, ASCII hex chars: `A013`, `BA13`, `B413`, `B313`, `8813` |
| 2–3 | uint16 LE counter |
| 4–15 | `00000000000000` (14 bytes; for B413/B313 the 16-byte protobuf header is `bytes[2..16]`) |
| 16– | protobuf payload (B413/B313 only) |

Dispatch on type:

- **`A013`** — device info (not parsed).
- **`BA13`** — config (not parsed).
- **`B413`** — protobuf **response**. Ack it; if `counter == current request
  counter`, parse `WrapperProto` from `msg[16..]`, complete the pending
  request, increment the counter.
- **`B313`** — protobuf **request** (device → app; e.g. event notifications).
  Parse `WrapperProto` from `msg[16..]`, emit events, handle.
- **`8813`** — this is the *app's ack format*, not received (see 5.6).

### 5.6 Acknowledgements

For every received `B413`/`B313`, the app must ack. Ack payload =
`8813` || original msg bytes [2..4] (the counter) || counter copy ||
`00000000000000` (14 zero bytes). This ack is sent via the normal framing path
(`WriteMessage` → length/CRC/COBS/chunks, header-prefixed).

### 5.7 App → device protobuf request format

Payload `P` for a protobuf request:

```
"B313" (2 bytes ASCII)
|| LE16(requestCounter)     // 2 bytes
|| 0x00 0x00                // 2 bytes
|| LE32(protobufLength)     // 4 bytes (BitConverter LE32)
|| LE32(protobufLength)     // 4 bytes again
|| protobufBytes
```

Send through the framing path. Wait up to 5 s for the matching `B413`
response (same counter); on success increment `requestCounter`. One request
in flight at a time (the response event gates the next).

### 5.8 Raw (non-framed) writes

The handshake bytes (5.4) bypass framing: each is `headerByte || rawBytes`
written as a single chunk via the writer characteristic. Post-handshake,
all `SendBytes` calls prefix the dynamic header byte.

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

1. Connect GATT (with retry loop until connected; device must be in pairing
   mode — blue blinking light — hold power button a few seconds).
2. Discover services; read serial/firmware/model; subscribe to battery
   notifications (read initial value).
3. Subscribe to device-interface notifier (CCCD) — the data channel.
4. **Handshake** (5.4). Failures here abort setup.
5. Subscribe to measurement/control/status characteristics (CCCD each).
6. `WakeDevice()` → `LaunchMonitorService.WakeUpRequest`.
7. `StatusRequest()` → current `StateType` (sets "ready" = `WAITING`).
8. `TiltRequest()` → device tilt (roll/pitch).
9. `SubscribeToAlerts()` → `EventSharing.subscribe_request` with
   `LAUNCH_MONITOR`.
10. If configured: `StartTiltCalibrationRequest`.
11. Send `ShotConfigRequest` with settings: temperature (°F default 60),
    humidity (default 1), altitude (m, default 0), air density (default 1),
    tee range = tee distance (ft, default 7) × 1/3.281.

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

- **Shot**: id (autogen), deviceShotId (unique index — dedup across sessions),
  timestamp, shotType (practice/normal), ballSpeedMph, launchAngle,
  launchDirection, spinAxisDeg, totalSpin, sideSpin, backSpin, clubSpeedMph,
  faceAngle, path, attackAngle, rawMetrics (proto bytes, optional, for future
  re-parsing).
- **Setting keys** (DataStore): deviceName (default "Approach R10"), autoWake
  (default true), calibrateTiltOnConnect (default false), temperature (60),
  humidity (1), altitude (0), airDensity (1), teeDistanceFt (7), debugLogging
  (false), reconnectIntervalS (5).

---

## 9. UI Design

Single activity, three tabs:

1. **Device** — connection state machine visualization (disconnected →
   connecting → handshake → ready), model/firmware/serial/battery, current
   state chip (WAITING/RECORDING/…), tilt, error banners, reconnect button,
   pairing flow for unbonded devices.
2. **Shots** — most recent shot detail card (big numbers: ball speed, carry-
   relevant metrics, spin axis visual) + scrolling table of all shots
   (ball/club/swing columns like the desktop console output). Filter
   practice/normal.
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
- Port `Cobs`, `Crc16`, byte helpers (unit-test against known vectors from
  the desktop implementation's debug output).
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

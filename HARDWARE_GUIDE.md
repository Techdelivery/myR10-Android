# R10 Hardware Validation Guide — M1 / H2

This is the runbook for the **real-device handshake validation** (task H2, the M1
acceptance gate). Everything up to this point is built and unit-tested; this page
is what you do **on the laptop with the R10 in hand**.

> **You own H2.** I can't drive the radio from here. When something doesn't match,
> paste me the hex log (see §7) and I'll diagnose against `DESIGN.md`, fix it, and
> update the golden + design if the design itself was wrong.

---

## 0. Read this first — phone vs emulator

- **A physical Android phone is required to connect to a real R10.** Android
  emulators run a *virtual* Bluetooth stack and **cannot connect to real external
  BLE peripherals**. Use a real phone.
- Use the emulator only for a UI/permission smoke test (does the screen render,
  does the permission dialog appear). It will never find the R10.
- **Android 12+ (API 31+) is strongly recommended** — that's where the runtime
  `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` permissions live. The app supports
  Android 8+ (API 26), but 12+ is the clean path.

---

## 1. Prerequisites

- [ ] A physical Android phone (12+), with **Developer options + USB debugging** on.
- [ ] The **Approach R10** launch monitor, charged.
- [ ] `adb` on the laptop (Android platform-tools). Check: `adb version`.
- [ ] This repo cloned on the laptop, on branch `main`, up to date:
      `git pull origin main`.
- [ ] JDK 17+ and an Android SDK with **platform android-36** + **build-tools 36.0.0**
      (only if you build the APK on the laptop — see §2A).

---

## 2. Get the APK onto the phone

### 2A. Build on the laptop (preferred)

```bash
cd myR10-Android
# point Gradle at your SDK (create local.properties if missing)
echo "sdk.dir=/path/to/your/android-sdk" > local.properties
# if java isn't on PATH, export it, e.g.:
# export JAVA_HOME=/path/to/jdk-17   (or 21)
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2B. Or install a prebuilt APK

If someone hands you `app-debug.apk`:

```bash
adb install -r app-debug.apk
```

The app is **R10 Monitor** (`com.techdelivery.r10`).

---

## 3. Confirm the advertised name matches the setting  ⚠️

The app **scans by advertised Bluetooth name**, defaulting to **`Approach R10`**
(from settings). If your unit advertises a different name, the scan will find
nothing.

- Check the real advertised name: in the phone's **Settings → Bluetooth**, or
  `adb shell` + a BLE scanner app, or nRF Connect.
- If it differs from `Approach R10`, **tell me before the run** — there is no
  settings screen yet (only the DataStore default), so I'll either change the
  default or add a quick name field. Don't burn time on a scan that can't match.

---

## 4. Put the R10 into pairing mode

**Hold the power button a few seconds** until the **blue light blinks**
(DESIGN §7.1). The device must be in pairing mode before you start the app.

---

## 5. Start the golden capture BEFORE you tap Start

Open a terminal on the laptop and start capturing the protocol log. `-v raw`
gives clean `TX <hex>` / `RX <hex>` lines — exactly the golden format:

```bash
adb logcat -c                         # clear old logs
adb logcat -s R10HEX -v raw > session-r10.hex
```

Leave this running. You'll stop it (Ctrl-C) after the run.

> The hex pane on screen is **not** copy-selectable, so logcat is the capture
> path. The pane is for you to watch live; logcat is for the file.

---

## 6. Run the validation

1. Open **R10 Monitor**.
2. Tap **Start monitor**.
3. Grant the permission prompts:
   - **Bluetooth** (`BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT`) → Allow
   - **Notifications** (`POST_NOTIFICATIONS`) → Allow
   - If you deny, the screen shows an inline "permission denied" message and
     nothing crashes — that's the expected G5 behavior. Tap Start again and allow.
4. Watch the screen + the `session-r10.hex` file. The status should move:
   `SCANNING → CONNECTING → HANDSHAKE → READY`.

What you should see on screen:
- **Device card**: Model, Firmware, Serial, Battery % populated.
- **Readout card**: WakeUp status, State (a `StateType`), Tilt (roll/pitch).
- **Hex pane**: TX/RX lines streaming.

---

## 7. M1 acceptance checklist (this is H2)

Tick these off from the screen + the hex file:

- [ ] Full §7.1 setup completes with **≤ 1 manual retry**.
- [ ] UI shows **Model** (`0x2A24`), **Firmware** (`0x2A28`), **Serial**
      (`0x2A25`), **Battery %**.
- [ ] **WakeUpResponse** + **StatusResponse (StateType)** + **TiltResponse
      (roll/pitch)** are visible in the readout card.
- [ ] In `session-r10.hex`, the **first TX** is the handshake literal:
      `TX 00 00 00 00 00 00 00 00 00 00 01 00 00`
- [ ] The **first RX** body starts with the prefix
      `01 00 00 00 00 00 00 00 00 01 00 00` and the **dynamic header is at
      byte index 12** of that body.
- [ ] Every **later TX** chunk is prefixed with that dynamic header byte (not `00`).
- [ ] After the handshake you see the request/response pairs (WakeUp, Status,
      Tilt, subscribe, ShotConfig) each followed by an `88 13 …` ack on RX.

If all tick → **M1 passes.** Go to §8 to lock in the golden.

---

## 8. Save the golden file

Stop the capture (Ctrl-C), then:

```bash
mkdir -p protocol/src/test/resources/golden
cp session-r10.hex protocol/src/test/resources/golden/session-r10.hex
```

Verify the replay now runs (it was skipping before; now it must **pass**):

```bash
./gradlew :protocol:test --tests '*GoldenReplayTest'
```

- Before the golden existed: `1 test, 1 skipped`.
- Now: `1 test, 0 skipped, 0 failures`.

Commit it:

```bash
git add protocol/src/test/resources/golden/session-r10.hex
git commit -m "test(protocol): golden session from real R10 (H2 capture)"
git push origin main
```

---

## 9. Report back to me

If anything in §7 **failed or looked odd**, paste into the chat:

1. **The `session-r10.hex` contents** (or the first ~40 lines around the failure).
   This is the single most useful thing — I can read the framing/COBS/CRC directly.
2. **What the screen showed** (a screenshot of the device + readout cards).
3. **Where it stopped** (e.g. "stuck at HANDSHAKE", "scan found nothing",
   "battery shows —", "GATT 133 on connect").

I'll map the bytes against `DESIGN.md` §5 and fix framing / handshake / dispatch,
add a regression test, and update the design if reality contradicted it.

---

## 10. Troubleshooting

| Symptom | Likely cause | What to do |
|---|---|---|
| Stuck at **SCANNING**, never connects | Advertised name ≠ `Approach R10`, or not in pairing mode, or BLE off | Re-check §3 + §4; confirm phone Bluetooth is on |
| "Bluetooth unavailable" / adapter null | No BLE on the device (e.g. emulator) | Use a real phone (§0) |
| **Handshake timed out** (status ERROR) | Reply prefix mismatch or dynamic-header offset | Paste the first RX line — I'll check the prefix + index 12 |
| Connects then **GATT 133** / drops | Bonding/link issue, or another app holds the GATT | Forget the R10 in Bluetooth settings, re-pair; close nRF Connect; retry |
| **Bond prompt** never completes | Bonding needs the pairing-mode window | Ensure R10 is in pairing mode when you tap Start |
| Device info shows **—** | Device-info reads failed | Note it; reads are GATT-level — paste logcat around the connect |
| Battery shows **—** | Battery characteristic read empty | Note it; battery = value byte 0, may need the initial READ path |
| Nothing in `session-r10.hex` | logcat filter wrong / service not started | Confirm you ran `adb logcat -s R10HEX -v raw` and tapped Start |

---

## 11. H3 — M1 gate (after H2 passes)

- [ ] `./gradlew build` green on a clean checkout.
      **Note:** this container's 2 GiB cap blocks the release+lint variants; run
      the full `build` on a **≥ 4 GiB** host. On a constrained box, the meaningful
      gate is `:protocol:test` + `:app:testDebugUnitTest` + `:app:assembleDebug`.
- [ ] A **second** hardware run after any fix shows **no regression** vs the saved
      golden (`GoldenReplayTest` still passes).
- [ ] `DESIGN.md` updated for any field observation that contradicted it.

---

## Quick reference

| Thing | Value |
|---|---|
| App | R10 Monitor — `com.techdelivery.r10` |
| Pairing | Hold power button → blue blinking |
| Default scan name | `Approach R10` (settings `deviceName`) |
| Capture cmd | `adb logcat -s R10HEX -v raw > session-r10.hex` |
| Golden path | `protocol/src/test/resources/golden/session-r10.hex` |
| Replay test | `./gradlew :protocol:test --tests '*GoldenReplayTest'` |
| First TX literal | `00 00 00 00 00 00 00 00 00 00 01 00 00` |
| First RX prefix | `01 00 00 00 00 00 00 00 00 01 00 00` (dyn header @ index 12) |
| Ack base | `88 13` + original type + `00` |

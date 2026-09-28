# R10 Hardware Validation Guide — M1 / H2

This is the runbook for the **real-device handshake validation** (task H2, the M1
acceptance gate). Everything up to this point is built and unit-tested; this page
is what you do **on the laptop with the R10 in hand**.

> **You own H2.** I can't drive the radio from here. When something doesn't match,
> paste me the hex log (see §7) and I'll diagnose against `DESIGN.md`, fix it, and
> update the golden + design if the design itself was wrong.

---

## 0. Read this first — phone vs emulator

### ⚠️ Do NOT disable the Garmin apps

**Never run `pm disable-user` on the Garmin apps on this phone.** The owner has
activities tracked in Garmin Connect and Garmin Golf, and disabling those apps
risks losing them. Do not do it as part of an install, a validation session, or a
troubleshooting step.

**You do not need to.** Coexistence is measured, not assumed: the app completed the
full §7.1 setup to `READY` — handshake, all five requests, device info — *while
Garmin held the same R10*. BLE multiplexes GATT clients onto one link, so our
connection and theirs coexist. The one thing that needs the device silent is
**fresh discovery** (unpairing and finding the R10 again), because the R10 stops
advertising while any client holds the link — and that is a single, explicitly
destructive step at the end of the runbook (§7, K3), not a precondition for
anything else. For an already-paired R10 the **bonded-direct** path is used, which
does not scan and does not care who else is connected.

**If discovery mysteriously finds nothing**, that is the one symptom to check:

```bash
adb shell dumpsys bluetooth_manager | grep -A 3 "ACL holders"
```

It will show whether another app is holding the R10's link. Report it — do not
"fix" it by disabling someone's apps.

**Check the current state before you start**, in case an earlier session left them
disabled (see the phone-state item in §7):

```bash
adb shell pm list packages -d | grep garmin   # must print nothing
```

Anything listed there is a Garmin app someone turned off, and needs re-enabling:

```bash
adb shell pm enable com.garmin.android.apps.connectmobile
adb shell pm enable com.garmin.android.apps.golf
```

### A physical phone vs the emulator

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
(from the Settings tab's device-name field, which persists to DataStore). If
your unit advertises a different name, the scan will find nothing.

- Check the real advertised name: in the phone's **Settings → Bluetooth**, or
  `adb shell` + a BLE scanner app, or nRF Connect.
- If it differs from `Approach R10`, set it in the app: **Settings tab → device
  name**, then Start the monitor. Don't burn time on a scan that can't match.
- **This only applies to an unpaired R10.** A paired R10 stops advertising a
  usable name (DESIGN §4) and is reached through the bonded-direct path, so a
  name mismatch is not a cause of failure once you have paired once.

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

## 7A. Later-session checks (M2 / M3 / R1–R3)

This guide's §1–§8 cover the first-pair M1/H2 run. Everything below is for a
later session, when the unit is already paired and the golden file is
committed. M2/M3 acceptance lives in `ROADMAP.md` (K1/K2); R1–R3 are merged
code with **no hardware verification recorded yet**.

- [ ] **Shots tab, screen stays on.** `adb shell dumpsys power | grep mWakefulness`
      reads `Awake` on the Shots tab with the phone unplugged, and returns to
      normal on the Device and Settings tabs. (ROADMAP R1.)
- [ ] **Tilt banner on the Shots tab.** Tip the R10 up: the banner appears with
      live pitch/roll. Lay it flat: the banner clears by itself. The trigger is
      the device's own `PLATFORM_TILTED`, not an app threshold. (ROADMAP R2.)
- [ ] **Shot selection.** Select shot #1, hit #2 — selection stays on #1 and #2
      appears in the list. Tap #2: the detail card switches. Tap #1: it comes
      back. (ROADMAP R3.)
- [ ] **Export shows a validation result.** Settings → Export writes the file and
      reports `N rows OK`, or `N/M rows OK · K problem(s): …` with line numbers.
      (ROADMAP R4.)
- [ ] **K2 — history survives a restart.** Kill the app, relaunch: shots still
      listed. Then a forced disconnect (R10 out of range / Bluetooth off) and
      confirm reconnect backs off instead of hot-looping.
- [ ] **K3 — H4 residual, destructive.** Do this *last*: Settings → Bluetooth →
      Forget `Approach R10`, power-cycle the R10, then run `ScanDumpActivity` and
      confirm the `0xFE1F` advertisement form returns and the production
      `ScanFilter` matches it. Unpairing drops the working connect path, so
      re-pair afterwards.
- [ ] **Phone state: Garmin apps still enabled.** This is a *check*, not a
      cleanup step — see the warning in §0. The Garmin apps must **not** be disabled
      at any point; the owner tracks activities in them. Run
      `adb shell pm list packages -d | grep garmin` and expect no output. If
      anything is listed (e.g. an earlier session left them off), re-enable with
      `adb shell pm enable com.garmin.android.apps.connectmobile` and
      `adb shell pm enable com.garmin.android.apps.golf`.
      Also undo `svc power stayon true` if you set it.

---

## 7B. Full acceptance run — R5 (club), R6 (delete), R7 (protobuf store)

Everything here is unverified on hardware as of 2026-09-28. R7 replaced the shot
store outright, so this run is the first time `files/shots.bin` has ever been
written by a real device. Run it in order: the later steps assume the earlier ones
passed, and a failure early on invalidates the rest.

### Before you start

- [ ] `adb devices` shows one device, and the R10 is charged and paired.
- [ ] **Decide what to do about the old `shots.csv`.** The build has no CSV→proto
      migration, so the Shots tab opens empty even though the CSV exists. Either
      accept that (clear app data now, so the state is known) or pull it off the
      device first:
      `adb shell run-as com.techdelivery.r10 cat files/shots.csv > old-shots.csv`
- [ ] Capture the starting state, so a later "empty history" is provable rather
      than suspected:
      `adb shell run-as com.techdelivery.r10 ls -la files/`

### 1. First shots land in the new store

- [ ] Start the monitor, hit **three** balls (mix a couple of practice swings with
      a real shot), and watch the Shots tab.
- [ ] Expect: three rows, newest first, each with ball speed and spin populated.
- [ ] Expect: `files/shots.bin` now exists. It should **not** grow `shots.csv`:
      `adb shell run-as com.techdelivery.r10 ls -la files/`
- [ ] Expect: no error banner on the Shots tab, and logcat clean:
      `adb logcat -s R10UI R10DIAG AndroidRuntime`
- [ ] If `shots.bin` is missing or the tab is empty, stop here. Everything below
      assumes the store is being written.

### 2. The club picker, end to end

- [ ] Settings tab: untick **Putter** and **5 Wood**. Leave the rest ticked.
- [ ] Expect: the grid updates immediately; the change survives leaving and
      re-entering the Settings tab.
- [ ] Shots tab: select a shot, open **Pick a club**.
- [ ] Expect: the list offers the clubs you own, and **neither** Putter nor
      5 Wood appears. Order is bag order (D, woods, hybrids, irons, wedges, P).
- [ ] Pick **7I** on that shot.
- [ ] Expect: the detail card shows `7I`; the list row reads `#n · 7I · 14:32:07 · 155 mph`;
      a **Clear** button appears next to the picker.
- [ ] Hit one more ball without touching the picker.
- [ ] Expect: the new shot is tagged `7I` too — the current-club stamp.
- [ ] Now pick a different club (say **GW**), then go back and **Clear** the first
      shot's tag.
- [ ] Expect: that shot shows no club, the row loses `7I`, and the shot itself is
      still there. Clearing a tag is not deleting a shot.

### 3. The bag survives a restart

- [ ] Force-stop and relaunch: `adb shell am force-stop com.techdelivery.r10`
      then open it.
- [ ] Expect: the Settings grid still shows Putter and 5 Wood unticked, and the
      shots still carry their `7I` / `GW` tags.
- [ ] This is the check that the tags are in the store and not just in memory.

### 4. Delete (the irreversible one)

- [ ] Select the **middle** of your three shots, then **Delete this shot**.
- [ ] Expect: a dialog naming the shot — `#id`, time, ball speed, club. Read it
      before tapping. Nothing is deleted until you confirm.
- [ ] Tap **Cancel** first and confirm the row is still there.
- [ ] Delete for real.
- [ ] Expect: that row is gone; the other two are unchanged and in order; the
      detail card falls back to the newest shot; no empty card.
- [ ] Delete the **newest** shot next.
- [ ] Expect: the next newest becomes the detail card.
- [ ] Expected cost: there is no undo. This is the point of the confirmation.

### 5. Delete does not collide across sessions (the identity rule)

This is the bug the review caught, and it only shows up across two sessions.

- [ ] Power-cycle the R10 (off, wait, on). Its `shot_id` sequence restarts at 1.
- [ ] Reconnect and hit **two** shots. They will carry shot ids that already exist
      in your history.
- [ ] Expect: both new shots appear as new rows, not suppressed as duplicates.
- [ ] Now select one of the **old** shots that shares a `shot_id` with a new one,
      and delete it.
- [ ] Expect: **only that one** disappears. The other shot with the same `shot_id`
      must survive. If both vanish, the identity fix regressed — that is a P0.

### 6. Export is still a readable CSV

- [ ] Settings → **Export shots to CSV**.
- [ ] Expect a message of the form `Wrote r10-shots-<stamp>.csv (N bytes) · N rows OK`
      and an absolute path.
- [ ] Pull it and open it: `adb pull <path from the message> .`
- [ ] Expect: a header row containing `schema_version` and `club_label`; one row
      per shot; the mph/rpm/degree values you saw in the app; the `club_label`
      cell holding `7I` / `GW` where tagged.
- [ ] Re-export after a delete and confirm the deleted row is absent — an export
      is a copy taken at that moment.

### 7. The store is really protobuf

- [ ] Pull the store: `adb shell run-as com.techdelivery.r10 cat files/shots.bin > shots.bin`
- [ ] Confirm it is not CSV: `file shots.bin` / `head -c 64 shots.bin | od -c`
      — you should see binary, not `shot_id,shot_type,...`.
- [ ] Decode the first record to prove the framing is plain protobuf:
      write the varint-length prefix off the front, then `protoc --decode_raw <
      record.bin`. Field 1 should be the store's version, and a shot record should
      show a nested `metrics` message with the device's own numbers.
- [ ] Expect: no field that means "mp" or "rpm" — the store holds what the R10
      sent, and the app converts on read. That is why the CSV is an export and not
      the store.

### 8. Damage is survivable (optional, only if you want to prove it)

- [ ] Take a copy of `shots.bin` first: this deliberately corrupts the file.
- [ ] Chop the last 12 bytes: `dd if=shots.bin of=truncated.bin bs=1 count=$(( $(stat -f%z shots.bin) - 12 ))`
- [ ] Push it back: `adb push truncated.bin /data/local/tmp/ && adb shell run-as com.techdelivery.r10 cp /data/local/tmp/truncated.bin files/shots.bin`
- [ ] Relaunch the app.
- [ ] Expect: the **earlier** shots still load, and `validate()` / the export
      message names the truncated record. A torn tail must cost the tail, not the
      session.
- [ ] Now tag a club on a surviving shot.
- [ ] Expect: either the tag saves, or a banner says the history is damaged and
      to export first. It must **never** silently drop the remaining records.
- [ ] Restore the good copy afterwards and relaunch.

### 9. What to send back

- [ ] Which step first went wrong, and the exact screen state.
- [ ] `adb logcat -d -s R10UI R10DIAG AndroidRuntime -t 200`
- [ ] The export message text, verbatim.
- [ ] `adb shell run-as com.techdelivery.r10 ls -la files/`
- [ ] For step 5 or 8, the outcome matters more than the reason: "both shots with
      the same id disappeared" is the report I need.

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

# R10 Monitor — Roadmap

Forward-looking tracker. `TODO.md` is the historical M0–M3 execution record
(what was built, what each step was verified against, and every hardware finding).
This file is **what to do next** and why.

Last updated: 2026-09-26 — R1–R4 shipped (PRs #5–#7 merged); R5 is the next
feature to build.

---

## Where we are

Working end-to-end on real hardware: pair → §7.1 setup → `READY` → shots decoded,
displayed, persisted to CSV. **2 real shots recorded and reviewed by the user.**

| Milestone | Code | Hardware |
|---|---|---|
| M0 scaffold | done | gate closed (K4) |
| M1 protocol + connect | done | verified (H2/H3/H4) |
| M2 shot data | done | **partial** — error alerts verified live, shot values not yet eyeballed |
| M3 persistence/export/reconnect | done | **not yet exercised** |

---

## Now — UI features

R1–R4 came from the user driving the real app and **shipped 2026-09-26**
(PR #5 keep-screen-on, #6 shots-tab-ux, #7 csv-validation). R5 is the next
feature to build. Ordered by how much they get in the way of using the app at
the range.

- [x] **R1. Keep the screen on while the Shots tab is active.**
  The screen sleeps mid-session and you lose the shot you just hit. Set
  `keepScreenOn` on the window while tab == Shots, and release it on every other
  tab so the phone can still sleep in the pocket.
  - Verify: `adb shell dumpsys power | grep mWakefulness` stays `Awake` on the
    Shots tab with no charging; goes back to normal on Device/Settings.

- [x] **R2. Bad-pitch indication on the Shots tab.**
  The R10 refuses to shoot when it is not level, but that currently only shows up
  on the Device tab. The Shots tab is where you are looking when you hit, so the
  warning belongs there.
  - Use the **device's own** `PLATFORM_TILTED` alert (`ErrorAlert.rollDeg` /
    `pitchDeg` already carry the numbers) rather than inventing a threshold in the
    app — the R10 knows its own tolerance, a locally-guessed one can disagree with
    it and be worse than nothing.
  - Show the live pitch/roll alongside the warning so "how far off" is visible.
  - Needs a typed tilt reading in `DeviceStateHolder` (today it is a formatted
    string, which is fine for display and useless for logic).
  - Verify: tip the R10 up → banner appears on the Shots tab with the numbers;
    lay it flat → banner clears.

- [x] **R3. Tap a shot to select it and see its numbers.**
  Right now only the newest shot has a detail card, so you cannot compare this
  shot against the previous one. Make the list rows clickable:
  - clear visual indication of which shot is selected (not just a colour tweak);
  - the detail card shows the **selected** shot, falling back to the newest when
    nothing is selected;
  - a new shot must not steal the selection you deliberately made.
  - Verify: select shot #1, hit shot #2 → selection stays on #1 and #2 appears in
    the list; tap #2 → detail switches; tap #1 again → back.

- [x] **R4. Validate the CSV.**
  The export is currently trusted, not checked: `exportSnapshot` copies the file
  and reports the byte count, and `decode` silently skips malformed rows — so a
  torn or truncated file exports "successfully" with rows quietly missing. Add a
  real validation pass and surface it in-app.
  - Check: header present and exactly the expected columns; every row has the
    expected column count; numeric fields parse; `shot_type` is a known enum name;
    `raw_metrics_hex` is even-length and valid hex.
  - Report per-line problems with line numbers, not just a pass/fail — a bare
    "invalid" is undiagnosable in a bug report.
  - Wire the result into the Export button message: `N rows OK` or
    `N rows, M problems: …`.
  - Round-trip check: `decode(encode(shot)) == shot` over a fuzz set, so a
    formatting change cannot silently lose precision.
  - Verify: `ShotCsvStoreTest` — clean file passes, injected torn row reported
    with its line number, wrong column count reported, bad hex reported.

- [ ] **R5. Assign a club to a shot (Shots tab → CSV → detail).**
  The R10 reports club *metrics* (`ClubDisplay`: club speed, face/path/attack
  angle) but never *which club* you swung. Let the user tag a shot with the club
  they used, persist it, and show it with the shot.
  - **User-entered label, not device data.** The device cannot supply the club
    identity, so treat it as an annotation on an otherwise device-owned shot —
    keep it separate from `ClubDisplay`. (If a club-type field turns up in the
    proto, use it as the default selection, not the source of truth.)
  - **Selection UX:** hang the club picker off the **selected** shot (R3 already
    gives selection). Start with a fixed golf set — Driver, 3W/5W, 3–9 iron,
    PW/GW/SW/LW, putter — defined in one place so it can become configurable
    later. A "current club" that stamps incoming shots is a nice-to-have on top.
  - **Persistence is the hard part.** The CSV store is append-only, so "change a
    shot's club" is not an in-place edit. Choose deliberately between (a) a store
    update that rewrites the row, (b) a `shot_id → club` sidecar, or (c) a
    current-club stamp at arrival time — and write the choice down. Do not let the
    store drift half-mutable.
  - **Schema change:** a new `club_label` column bumps the column count R4
    validates against. Existing CSVs without it must still load — bump a schema
    version or make `decode`/`validate` tolerate the missing column, so a
    previously-exported file never becomes "invalid".
  - **Display:** show it in `ShotDetailCard` and compactly in the list row, so a
    tagged shot reads "7-iron · 155 mph …" instead of burying it.
  - Verify: select a shot → pick "7-iron" → it shows in the detail card; kill +
    relaunch → the tag survives; export → `club_label` is present and the file
    still validates; an old CSV without the column still loads.

---

## Next — hardware acceptance still open

Carried over from `TODO.md` Phase K.

- [ ] **K1 (remainder). M2 acceptance: hit balls and eyeball the numbers.**
  Ball speed in mph, spin in rpm, angles in degrees — sane against what you saw.
  `shot_id` dedup holds under device re-push. `STANDBY` auto-wakes.
  OVERHEATING and RADAR_SATURATION surface (hard to trigger deliberately; may stay
  unit-only).
  - Already proven live: `PLATFORM_TILTED — WARNING` end-to-end
    (B313 → AlertRouter → AlertMirror → UI).
- [ ] **K2. M3 acceptance.** Kill the app → relaunch → history still listed.
  Export CSV → opens with the same rows (R4 makes this checkable rather than
  hopeful). Forced disconnect → reconnect backs off, no hot loop.
- [ ] **K3. Close the H4 residual.** Forget the R10 → power-cycle → confirm the
  `0xFE1F` advertisement form returns and the production `ScanFilter` matches it.
  See DESIGN §4: a **paired** R10 advertises a stub with no name and no service
  UUID, so only the bonded-direct path can reach it. Do this **after** K1/K2 —
  unpairing drops the working connect path.

---

## Known bugs and debt

- [ ] **Handshake reply accumulation is still per-chunk.** `HandshakeStateMachine`
  matches each stripped body independently (reference behaviour). Fine against the
  golden capture; a reply split across chunks in a way the reference tolerates
  would not be handled. Deferred pending evidence — do not fix speculatively.
- [ ] **COBS ≥255-byte run quirk.** A run of ≥255 consecutive non-zero bytes
  drops the 255th byte (pinned in `CobsTest`). Faithful to the reference and
  unreachable with real frames; documented, not fixed.
- [ ] **No Robolectric.** Service wiring (L1 `autoWake`, the `historyError`
  mirror) is grep-verified only. Adding Robolectric is its own task.
- [ ] **No detekt/ktlint.** A style gate would fail on pre-existing formatting.
- [ ] **Deprecated BLE APIs.** `BleTransportImpl` uses the pre-API-33
  `writeCharacteristic`/`setValue` forms (9 warnings). They work; migrating to
  `GattCallback`-free `writeCharacteristic(bytes, type)` is a separate change and
  must not be done blind — this code is field-verified.
- [ ] **`TabRow` deprecation** in `MainActivity` (PrimaryTabRow/SecondaryTabRow).
- [ ] **Room still not used.** DESIGN §8 names Room; CSV is a recorded deviation
  (no KSP release for the pinned Kotlin, and the old 2 GiB build cap). The cap is
  gone now, so Room is *possible* — but the CSV store is lossless here and doubles
  as the export, so there is no forcing function. Decide deliberately, not by
  drift.

---

## Later milestones

Calibration UI beyond the connect-time toggle · carry/distance modelling ·
unit-switching UI (mph/kph, yd/m) · multi-device support · log export beyond the
hex pane + CSV · GATT service-cache refresh workaround (only if a real 133 loop
shows up on hardware) · sharing the exported CSV via the system share sheet.

---

## Standing rules (unchanged, from TODO.md)

1. Never "clean up" a magic byte. They are field-verified.
2. Outbound proto sits after a 14-byte inner header (§5.7); inbound proto at
   `msg[16..]` (§5.5). Do not normalize either side.
3. Message type is two raw bytes (`B4 13`), never ASCII.
4. CRC mismatch → log + drop. Deliberate hardening.
5. Any on-hardware fix to framing constants needs hex-log evidence, a
   golden-expectation update, and a `DESIGN.md` correction if the design was wrong.

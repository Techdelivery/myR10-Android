# R10 Monitor — Roadmap

Forward-looking tracker. `TODO.md` is the historical M0–M3 execution record
(what was built, what each step was verified against, and every hardware finding).
This file is **what to do next** and why.

Last updated: 2026-09-27 — R5, R6 and R7 implemented and test-covered; all three
still need a hardware pass. R1–R4 merged (PRs #5–#7).

Legend: `[x]` done **and** verified · `[~]` code merged, not yet verified on
hardware · `[ ]` pending.

---

## Where we are

Working end-to-end on real hardware: pair → §7.1 setup → `READY` → shots decoded,
displayed, persisted to CSV. **2 real shots recorded and shown in the app**, but the
numbers have not been sanity-checked against what you saw at the range (that is
K1 below).

| Milestone | Code | Hardware |
|---|---|---|
| M0 scaffold | done | gate closed (K4) |
| M1 protocol + connect | done | verified (H2/H3/H4) |
| M2 shot data | done | **partial** — 2 real shots recorded and displayed; error alerts verified live; shot values not yet eyeballed (K1) |
| M3 persistence/export/reconnect | done | **not yet exercised** |

---

## Now — UI features

R1–R4 came from the user driving the real app and merged 2026-09-26
(PR #5 keep-screen-on, #6 shots-tab-ux, #7 csv-validation). **R1–R3 are still
unvalidated on hardware** — no `dumpsys power` reading, no tipped-unit session,
no tap-selection session has been recorded, and no unit test covers them. R4 is
test-covered (`ShotCsvFormatTest`). R5 is the next feature to build, and R6 is a
consequence of R5's store decision rather than a separate idea. Ordered by
how much they get in the way of using the app at the range.

- [~] **R1. Keep the screen on while the Shots tab is active.** *(code merged,
  not yet verified on hardware)*
  The screen sleeps mid-session and you lose the shot you just hit. Set
  `keepScreenOn` on the window while tab == Shots, and release it on every other
  tab so the phone can still sleep in the pocket.
  - Verify: `adb shell dumpsys power | grep mWakefulness` stays `Awake` on the
    Shots tab with no charging; goes back to normal on Device/Settings.

- [~] **R2. Bad-pitch indication on the Shots tab.** *(code merged, not yet
  verified on hardware)*
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

- [~] **R3. Tap a shot to select it and see its numbers.** *(code merged, not
  yet verified on hardware)*
  Right now only the newest shot has a detail card, so you cannot compare this
  shot against the previous one. Make the list rows clickable:
  - clear visual indication of which shot is selected (not just a colour tweak);
  - the detail card shows the **selected** shot, falling back to the newest when
    nothing is selected;
  - a new shot must not steal the selection you deliberately made.
  - Verify: select shot #1, hit shot #2 → selection stays on #1 and #2 appears in
    the list; tap #2 → detail switches; tap #1 again → back.

- [x] **R4. Validate the CSV.** *(merged + test-covered in `ShotCsvFormatTest`
  since R7 moved the format out of the deleted store; the in-app export message
  itself still wants one on-device run)*
  The export used to be trusted, not checked: it copied the store's file out
  and reported the byte count, and `decode` silently skips malformed rows — so a
  torn or truncated file exported "successfully" with rows quietly missing. Add a
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
  - Verify: `ShotCsvFormatTest` — clean file passes, injected torn row reported
    with its line number, wrong column count reported, bad hex reported.

- [~] **R5. Assign a club to a shot (Settings bag → Shots tab → CSV → detail).** *(implemented 2026-09-27, unverified on hardware)*
  The R10 reports club *metrics* (`ClubDisplay`: club speed, face/path/attack
  angle) but never *which club* you swung. Let the user tag a shot with the club
  they used, persist it, and show it with the shot. **Built 2026-09-27** —
  `club/GolfClub` (the canonical list), `ownedClubs` + `currentClub` in
  `SettingsRepository`, `ShotProtoStore.updateClub`, the `ShotDetailCard` club
  editor, and the arrival stamp in `R10ForegroundService`. The reasoning is in
  DESIGN §8; this section is the summary, not the source of truth.
  - **One canonical club list, in one place.** A single `Club` list ordered driver
    → putter, defined once and read by both the Settings bag editor and the
    Shots-tab picker. Never hardcode the set in two places.
  - **Wider coverage, abbreviated labels (from the first on-device pass
    2026-09-27; built).** The initial list missed clubs that are actually in a bag:
    **2 iron, 3 iron, 4 wood, 6 wood, 7 wood, 9 wood**. The list now covers the
    full fairway-wood ladder, the short irons, the half irons (`9.5I`…`6.5I`, the
    low-lofted approach clubs), hybrids (`2H`…`5H`, the rescue clubs), the wedges
    and the putter.
    - **Labels are the golf abbreviation**: `D`, `3W`, `4I`, `GW`, `P`. Bag order
      is the point — a column of 5-character names is noise in the picker, the list
      row and the CSV, and nobody reads "Pitching Wedge" faster than "PW". The
      abbreviation is the persisted `club_label`, so it is also the migration
      surface (below).
    - **The letters must not collide across categories.** A hybrid between a 3-iron
      and a 4-iron is `3H`, never `3I` — reusing a form already on disk would make
      two different clubs indistinguishable in the CSV forever. Irons take `I`,
      hybrids `H`, woods `W`, wedges are spelled out.
    - **Long names stay resolvable, forever.** `GolfClub.fromId` accepts the old
      long labels ("7 Iron", "Sand Wedge") as aliases, and `ownedClubs` written in
      the old form is normalized on read, so a bag saved by the previous build
      still shows ticked. Otherwise the first build of this feature would
      invalidate the labels it wrote last week — the same rule as the v1 CSV: a
      file this app wrote earlier must keep loading.
    - Verify: every club in your bag appears in the grid and in the picker; the row
      reads "7I · 155 mph"; a `club_label` written by the previous build still
      resolves to the right club instead of becoming an unknown label.
  - **Settings → "Clubs I own".** Checkbox grid over the full canonical list,
    multi-select. Default: all clubs owned, so a fresh install always has a
    usable picker. Stored in `AppSettings` (DataStore) and deliberately **not
    exported** — the CSV is device measurements plus annotations, not bag
    inventory.
  - **User-entered label, not device data.** The device cannot supply the club
    identity, so keep `club_label` separate from `ClubDisplay`. (If a club-type
    field turns up in the proto, use it as the default selection, not the source
    of truth.)
  - **Selection UX:** the club picker hangs off the **selected** shot (R3).
    Choices are the canonical list filtered to the clubs the user owns. If the
    owned set is empty, fall back to the full list — an unlabelable shot is worse
    than an unowned club. Compactly in the list row, so a tagged shot reads
    "7-iron · 155 mph …"; prominently in the detail card, where it is also
    editable and clearable.
  - **Current-club stamp on arrival: yes.** The last-picked club auto-tags each
    new shot, so a session at the range does not need a picker tap per ball. The
    stamp is always visible and always correctable in the detail card; the R10
    refusing to record while tilted is not a reason to also require a tap.
  - **Persistence (decided): a rewrite inside the store, not a sidecar.**
    `ShotProtoStore` is mutable and stays the only writer of `shots.bin`, under its
    mutex: `append`, `updateClub` (re-emit that one record), and `exportCsv`.
    A rewrite is read-all, apply the change, write temp + rename,
    so a kill mid-rewrite leaves the previous file intact, and the dedup index is
    rebuilt from the surviving records. A rewrite also **refuses on a damaged
    file** (a torn tail, a record that is neither header nor shot): a whole-file
    rewrite writes back only the records it could read, so applying one would
    delete everything past the tear. The export reports the damage instead. The UI never touches the file, so the store
    cannot drift half-mutable. A sidecar `shot_id → club` map was rejected: it
    gives two sources of truth for one fact.
  - **Schema evolution (decided, then superseded by R7): a `schema_version` leading
    column**, with `ShotCsvFormat.SCHEMA_VERSION`, a retained `HEADER_V1` (21
    columns), `decode` / `validateText` accepting every known version, and
    migration as an explicit `migrate()` under the mutex — never implicit inside
    `decode`, so reading a file never rewrites it. R7 kept the version column and
    `HEADER_V1` in the export format and dropped the store-side migration
    (protobuf needs none). Full rules in DESIGN §8.
  - Verify: Settings bag toggles and survives relaunch; select a shot → pick
    "7-iron" → it shows in the detail card and the row; hit a shot without
    touching the picker → it inherits the current club; change a tag on an older
    shot → row rewrites, other rows untouched, file still validates; kill the app
    mid-session → the file is not truncated; export → `schema_version` and
    `club_label` present and R4 validation still clean; **an old 21-column CSV
    still loads and still validates.**

- [~] **R6. Delete a shot.** *(implemented 2026-09-27, unverified on hardware)*
  A mis-hit practice swing, a bad session, or a row the user does not want in
  their history all need the same thing: remove it. This falls out of the R5
  decision that the store is mutable — once `updateClub` exists, deletion is the
  same rewrite with a different transformation, and leaving it out would make
  editing the file by hand the only way to drop a shot.
  - **Store:** `deleteShot(shotId)` in `ShotProtoStore`, under the same mutex and
    the same temp-file-rename rewrite as `updateClub`, rebuilding the dedup index
    from the surviving records. A missing `shot_id` is a no-op, not an error.
  - **UI:** delete from the shot detail card on the Shots tab, scoped to the
    **selected** shot, so it can never delete the wrong row by accident. Clearing
    the club tag stays a separate, non-destructive action.
  - **Confirmation is required** — data loss with no undo. The dialog must name
    the shot (`#id`, time, ball speed) so the user can see which row is about to
    go. A single "Delete" tap on a list row is not acceptable.
  - **Session state:** after a delete the selection is dropped (that shot is
    gone) and the detail card falls back to the newest shot, reusing the R3
    fallback rather than showing an empty card.
  - **Already-exported files are unaffected** — an export is a copy, so deleting
    a shot later does not rewrite CSVs the user already took off the device.
  - Verify: delete a middle shot → gone, rows either side intact and still in
    order, file still validates, detail falls back to the newest; delete the
    newest → next newest becomes the fallback; kill the app mid-rewrite → file not
    truncated, the shot either fully present or fully gone, never half-written;
    delete a shot, then have the device re-push it → it is written again (the
    dedup index was rebuilt, not left stale).

- [~] **R7. Store shots as protobuf instead of CSV.** *(built 2026-09-27, unverified
  on hardware)* The CSV was a derived, human-readable shadow of data the app already
  held losslessly: every `Shot` carries the R10's own `Metrics` bytes. So the store is
  now those bytes.
  - **Framing:** length-delimited records in one file (`shots.bin`) — protobuf's own
    stream encoding, a varint length per record. Chosen over a single `repeated`
    message, which is also pure proto but would rewrite the whole file per shot and
    make one bad byte cost the entire session.
  - **Header record** carrying `store_format_version`, then one `StoredShot` per
    shot: the device's `Metrics` plus `received_at_ms` and `club_label`, the only two
    facts the app owns. App-owned messages, not part of the device protocol.
  - **No CSV→proto migration.** Clearing storage was available, so the cut is clean
    instead of carrying a back-compat layer for a file the user chose to discard.
  - **CSV survives as the export**, produced from the store and still validated by
    the R4 rules — a readable file is the deliverable; it is just no longer where the
    data lives.
  - **Deleted with it:** `ShotCsvStore` and `ShotCsvRewriter` — the store no longer
    keeps a CSV file, and with it the store-side `migrate()` (protobuf needs no
    migration). **Kept, deliberately:** `schema_version`, `HEADER_V1` and the
    per-version row checks all survive in `ShotCsvFormat`, because the **export**
    format still carries them: an older exported file must still load and still
    validate clean (R4's rule), and that is R5's verify list, not a leftover. Added:
    `ShotRecordCodec`, `DelimitedRecords`, `ShotStoreValidation`.
  - Verify: hit shots, kill and relaunch → history intact; export → the same values
    as before in mph/rpm/degrees; a club tag survives; delete still works; and
    **each varint-delimited record in `shots.bin` decodes under
    `protoc --decode_raw`** (the check that the records are really plain protobuf
    and not app-private framing — the command parses one message, so it is handed a
    record *body*; it cannot read the varint length prefixes, so it cannot check
    the framing of the file as a whole).

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
  - Also close out the merged-but-unvalidated UI work while the unit is out:
    R1 `dumpsys power` stays `Awake` on the Shots tab and normal on the other
    two; R2 tip the unit and the banner shows real pitch/roll, lay it flat and it
    clears; R3 select shot #1, hit #2, selection stays on #1.
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
- [ ] **Detekt baseline still carries 6 pre-existing findings** (TODO K5), so
  new code can add a finding without the gate naming the file. Order and risk for
  clearing them is written down in TODO K5; the service sequencer is last.
- [ ] **Deprecated BLE APIs.** `BleTransportImpl` uses the pre-API-33
  `writeCharacteristic`/`setValue` forms (9 warnings). They work; migrating to
  `GattCallback`-free `writeCharacteristic(bytes, type)` is a separate change and
  must not be done blind — this code is field-verified.
- [ ] **`TabRow` deprecation** in `MainActivity` (PrimaryTabRow/SecondaryTabRow).
- [ ] **Room still not used.** DESIGN §8 names Room; CSV is a recorded deviation
  (no KSP release for the pinned Kotlin, plus a build-memory budget that varies
  by workstation — see the memory-restricted-workspaces note in DESIGN §8). The
  CSV store is lossless here and doubles as the export, so there is no forcing
  function. Decide deliberately, not by drift, and do not treat any one machine's
  memory limit as a project constraint.

---

## Later milestones

Listed once, in `TODO.md` under "Out of scope this pass" — keep that list
canonical; add new candidates there, not here.

Candidates already raised: calibration UI beyond the connect-time toggle ·
carry/distance modelling · unit-switching UI (mph/kph, yd/m) · multi-device
support · log export beyond the hex pane + CSV · GATT service-cache refresh
workaround (only if a real 133 loop shows up on hardware) · sharing the exported
CSV via the system share sheet.

---

## Standing rules (unchanged, from TODO.md)

1. Never "clean up" a magic byte. They are field-verified.
2. Outbound proto sits after a 14-byte inner header (§5.7); inbound proto at
   `msg[16..]` (§5.5). Do not normalize either side.
3. Message type is two raw bytes (`B4 13`), never ASCII.
4. CRC mismatch → log + drop. Deliberate hardening.
5. Any on-hardware fix to framing constants needs hex-log evidence, a
   golden-expectation update, and a `DESIGN.md` correction if the design was wrong.


# R10 Monitor — Roadmap

Forward-looking tracker. `TODO.md` is the historical M0–M3 execution record
(what was built, what each step was verified against, and every hardware finding).
This file is **what to do next** and why.

Last updated: 2026-09-28 — R5, R6 and R7 implemented, test-covered and review-clean
(PR #13, still open); all three still need a hardware pass. **The whole parked list
from the R5/R6/R7 review is now closed** — A (write ordering), B (an uninterpretable
record locking the store), C (the read ceiling), D (the rename-failure branch), E
(the dedup index) and F (a real losslessness bug in the club tag) are all built and
recorded in DESIGN §8. R1–R4 merged (PRs #5–#7).

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
    delete everything past the tear. The export reports the damage instead. An
    **append repairs** such a file first — dropping only the bytes past the tear
    and keeping every record the read could account for — because a store that
    refused to append would be permanently unable to record a shot. Both
    mutations are keyed on **`(shot_id, received_at_ms)`**, never the id alone:
    the R10 restarts its id sequence on every power cycle, so the file can hold
    yesterday's shot 1 and today's shot 1 at once. The UI never touches the file, so the store
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
  - **Store:** `deleteShot(shotId, receivedAtMs)` in `ShotProtoStore`, under the
    same mutex and the same temp-file-rename rewrite as `updateClub`, rebuilding
    the dedup index from the surviving records. Keyed on the **pair**, not the id:
    a power cycle restarts the R10's id sequence, so two sessions can each hold a
    shot 1 and deleting by id alone would take both. A missing
    `shot_id` is a no-op, not an error.
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

## Parked — resume points from the R5/R6/R7 review loop

Left over from a two-round review of the R5/R6/R7 branch (PR #13). Each is parked
with its evidence and the decision it is waiting on, so nothing has to be
re-derived. Ordered by how much it can hurt.

- [x] **A. Decide who owns ordering between the UI and the persist queue.** DONE
  2026-09-28 — the decision is in DESIGN §8 under "One writer: the queue owns
  ordering", and it is built, not just written.
  `ShotPersistSink` became `ShotWriteQueue`: a process-wide singleton on `R10App`,
  the only thing in the app that writes `shots.bin`. It carries every kind of write
  (`ShotWriteOp.Append` / `SetClub` / `DeleteShot`) in submission order, so the two
  named failures are impossible rather than merely unlikely — a delete can no longer
  be undone by a queued append, and a club pick can no longer land on a shot that
  was not on disk yet. The second needed one more line: the service now calls
  `submit` *before* `addShot`, so a shot is never visible before it is queued.
  - The choice made: the queue owns ordering (edits are queue entries), not a flush
    barrier, because one owner is easier to reason about than two writers with a
    handshake between them.
  - Two consequences taken deliberately: the queue is never closed by the service
    (app-lifetime, so a queued row is not lost to a timed-out drain on Stop), and
    the store's rewrites return a `WriteOutcome` instead of a `Boolean`, because
    `false` meant both "not found" and "damaged" and the UI was re-validating the
    whole file after every refused edit to tell them apart.
  - Verify: `ShotWriteQueueTest` — a delete and a club pick submitted behind a
    stalled queued append, edits in submission order, each refusal reported as
    itself, an edit not rejected by a full append queue, and `apply` on an
    unstarted queue rejected rather than hanging. `./gradlew check` green.

- [x] **B. A well-framed non-record makes the store permanently un-mutable.** DONE
  2026-09-28. The fix turned out to be one boolean, not a quarantine mechanism.
  `readRecordsUnlocked` set `damaged` for three different things, and only two of
  them make a rewrite lossy: a torn tail and a capped file both mean records the
  walk never read, so writing back what it did read would drop them. The third — a
  record that framed fine but is neither a header nor a shot — has its bytes in
  hand, and `updateClub` and `deleteShot` **already** carried such records through
  verbatim. The guard was the only thing stopping them.
  - So `damaged` is now `rewriteSafe` (positive polarity: the bug was one flag
    meaning "cannot read" *and* "cannot understand", which have opposite
    consequences), and `RecordScan` carries `framed` — every framed record,
    uninterpretable ones included — for the rewrites to write back, alongside
    `records` for what the UI loads and `validate` names.
  - The branch's own rule is kept *more* literally, not less: a rewrite still never
    destroys a record it cannot parse. Carrying a record forward is also what a
    forward-compatible format should do — a shot record from a newer
    `store_format_version` lands in exactly this case.
  - Preserve, don't accept: `validate()` still names it and the UI still never sees
    it. The two existing "a torn store must refuse the rewrite" tests still pass and
    still mean *torn tail*.
  - Verify: `ShotProtoStoreTest` — the real shots load beside such a record, a club
    tag works, a delete works, the record survives both byte for byte, `validate()`
    names it while still counting the real shots, and a torn tail still refuses both
    rewrites with the file untouched. The three behavioural tests fail against the
    old `damaged` clause and pass against the new one.
  - **Found while writing the test:** `StoredShot.received_at_ms` and
    `ShotLogHeader.store_format_version` are both field 1 varint, so a record
    carrying only a timestamp parses perfectly as a header and the store has no way
    to know it is not one. Not fixed here — a stray header mid-file is not read as
    the file's header (`validate` only checks the first) — but it is a real
    ambiguity in the record layout.

- [x] **C. `MAX_RECORDS` bounds neither allocation nor rewrite.** DONE 2026-09-28.
  The cap was applied to records that had already been materialised, out of a buffer
  that was already the whole file, so it bounded neither — and its KDoc credited
  itself with stopping "a corrupt length" from allocating forever, which is not what
  it did and not what stopped that either. `DelimitedRecords.read`'s bounds check is,
  by comparing every length against the bytes actually remaining; a test now pins
  the two apart so they cannot be confused again.
  - Replaced by `MAX_FILE_BYTES` (32 MiB), checked against `file.length()` **before**
    anything is allocated. Bytes, because bytes are what a read costs. Measured: a
    fully-populated record is 87 bytes (all three metric groups, every optional field,
    club label included) — the store keeps the device's own `Metrics` rather than
    derived text, so a shot costs almost nothing. 32 MiB is therefore ~385 000 shots,
    about 25 years of a 100-shot session three times a week. It is a ceiling, not a
    target; neither number is reachable on a real device, which is consistent with the
    old cap being decorative.
  - **A rewrite still cannot be bounded** — it writes back every record — so past the
    ceiling both rewrites refuse and the file is left byte-identical. Truncating the
    oldest shots is not an acceptable version of "bounded".
  - **The trap this walked into, and it was real.** `repairTailUnlocked` fixes a torn
    tail by writing back the bytes the walk accounted for; past the ceiling those are
    only the bytes that were *read*, so a repair would have deleted everything past it
    — and a repair is on the **append** path, so the loss would have arrived with the
    user's next shot. A file over the ceiling is now never repaired. Verified by
    deleting the guard: the test fails with the file rewritten down to the readable
    prefix.
  - Truncation is loud, which is the part that actually hurt: `loadAll()` now returns
    the shots *and* what reading them could not cover, and the Shots tab puts the
    problems on the existing error banner. A file this far over the ceiling is only
    fixed by `clear()`; appended shots past it are kept but land outside the readable
    window, which is the accepted cost (refusing the append would drop a real shot
    silently).
  - Verify: `ShotProtoStoreTest` — an oversized file loads what it could read, names
    the ceiling *in bytes*, refuses both rewrites with the file untouched, is never
    repaired on the append path, and a normal file reports no problems at all. Full
    `check` green: 280 app tests, 109 protocol.

- [x] **D. The `writeAllUnlocked` rename-failure branch has no test.** DONE 2026-09-28.
  The throw is the only thing protecting the original file after a failed rewrite,
  and it was the last branch in the store verified by reading alone.
  - **The seam was the decision, and the answer is a constructor parameter** —
    `rename: (File, File) -> Boolean = { source, target -> source.renameTo(target) }`,
    the same shape as the existing `recentKeyWindow` test hook. Rejected on the way:
    a read-only parent directory is ignored when the tests run as root, so it would
    pass on one machine and quietly test nothing on another; and making the
    destination a non-empty directory fails in the *read*, not the rename, so it
    would be a test of the wrong branch. Production behaviour is unchanged.
  - The message the user sees is now a sentence, not a path: the raw
    `could not replace /data/.../shots.bin` reached the Shots tab through the write
    queue's error banner. The queue's prefix became "could not save that change —"
    so the two read as one line.
  - **The part that was not written down anywhere:** a refused rewrite must leave
    the store able to make progress. A throw that bricked the store would turn one
    transient failure into permanent data loss, so a later append and a later
    rewrite are both asserted to work after one is refused.
  - **A trap worth recording.** The first version of the test injected a bare
    `{ _, _ -> !failRename }`, which short-circuits the *effect* as well as the
    failure: the store believed it had replaced the file while nothing had moved,
    and a later assertion passed for the wrong reason. The seam controls the failure,
    never the effect, and the test says so. Found by the test failing in a way that
    made no sense, which is the only reliable way to find this kind of thing.
  - Verify: `ShotStoreDurabilityTest` (new, 2 tests) — a failed rename reports the
    failure, leaves the original byte-identical, leaves the temp file for the next
    attempt, and still lets the history read; and the store writes normally
    afterwards, with the tag landing on the shot it was aimed at and no temp file
    left behind. Inverting the guard fails them. Full `check` green: 324 app tests,
    109 protocol, no new baseline entry.

- [x] **E. `ShotDedupIndex` has no test.** DONE 2026-09-28.
  LRU eviction, the `contains` touch, and the `recentKeyWindow` constructor hook all
  lost their coverage when `ShotCsvStoreTest` went with the CSV store; TODO.md L8
  records the same gap. The unit was untested because the store's own tests only
  ever drove it with the production window of 2000 and a handful of shots, so every
  path through the eviction logic was unreachable from a test. The window is a
  constructor parameter precisely so it need not be.
  - The class needed **no fix**. Verified by mutation rather than by reading:
    removing the `contains` touch fails `observingAKeyMakesItRecentSoItIsNotTheNextToGo`
    alone, and shifting the eviction bound by one fails five. The LRU logic was
    right; what was missing was anyone proving it.
  - What *was* wrong was the documentation, and it is a real edge: the index is
    bounded, a rewrite re-seeds it from the file, and that seed is bounded too — so
    after any club tag or delete the oldest shots fall out of it and a re-push of
    one of those is written again as a duplicate row. The window is what stops the
    index growing with the history, so this is a deliberate trade; but the honest
    guarantee is "a re-push is suppressed if its key is one of the last N stored",
    not "a re-push is suppressed". DESIGN §8 now says that. The device's in-session
    deduper is a separate mechanism and does not consult this index, so the window
    only widens a gap that already existed.
  - Verify: `ShotDedupIndexTest` (new, 11 tests) — fresh index knows nothing, add
    and re-add, least-recently-seen evicted, the `contains` touch, the window never
    exceeded over 500 keys, clear, and `seedFrom` rebuilding / skipping a
    keyless shot / keeping the newest. `ShotProtoStoreTest` gains four at the store
    level with `recentKeyWindow = 2`: a re-push inside the window is suppressed, one
    outside it is written again after a rewrite, a deleted shot is re-pushable at
    once, and `clear()` resets the index. Full `check` green: 308 app tests.

- [x] **F. Two loose assertions worth tightening when convenient.** DONE 2026-09-28.
  One was loose. The other was a **real bug**, and the clearest one this review has
  turned up.
  - *The export assertion* searched the whole file for the ball speed's digits, so a
    `shot_id` of 155 or a timestamp containing "155" satisfied it without the value
    being exported at all. It now decodes the row and compares **column by column**
    (ball speed, launch angle, club speed, face, attack, total spin, club label,
    arrival time), which is the claim it was making.
  - *"The device's bytes are byte-identical"* was only ever proven for a message this
    build fully understands, and it was false for the one path that rewrites a
    record. A club tag decoded the record and re-encoded it; protobuf-lite discards
    unknown fields on parse, so **tagging a club silently deleted every field a
    future version had added** — to the wrapper or to the device's own `Metrics` —
    at the moment the user picked a club. DESIGN §8's claim that a field added later
    "appears without a schema change" was true for appends and deletes and false for
    tags.
  - The asymmetry is what gave it away: `deleteShot` copies the records it keeps
    verbatim and always passed, so only the tag was affected. `anUnknownFieldSurvivesADelete`
    is kept as the control.
  - Fixed by **splicing** the one field (`ShotRecordCodec.withClubLabel`) instead of
    re-serializing: every other top-level field is copied as raw bytes, and the
    nested `metrics` run is never looked inside. `updateClub` still reads through
    `toShot` to find its record — reading was always lossless; only writing was not.
    A body the splicer cannot walk is refused rather than half-rewritten.
  - That refusal is why `ShotWriteQueue` now completes every edit's reply even when
    the store throws: a refusal that killed the writer would hang the caller and
    stall every later edit, which is worse than the bug it replaced.
  - Verify: `ShotRecordSpliceTest` (new, 7 tests) — an unknown field on the wrapper
    survives a tag, a *clear* and a re-tag; one inside the nested `Metrics` survives;
    re-tagging replaces rather than stacks the label; a delete preserves; a record
    the splicer cannot walk is refused. Two of them fail against the old re-encoding
    line. `ShotWriteQueueTest` gains a test that a throwing edit is rejected and the
    writer carries on. Full `check` green: 322 app tests, 109 protocol, no new
    baseline entry.

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


# Kotlin Coding Standard

The standard for Kotlin in this repository, and how it is enforced.

Three tools cover it, all wired into `./gradlew check` so `./gradlew build` cannot
go green with the standard unmet:

| Tool | Version | Covers | Config |
| --- | --- | --- | --- |
| [ktlint](https://pinterest.github.io/ktlint/) | 1.8.0 | Formatting, whitespace, import order, trailing commas | [`.editorconfig`](../.editorconfig) |
| [detekt](https://detekt.dev/) | 1.23.8 | Code smells, complexity, error-prone patterns, coroutine misuse | [`config/detekt/detekt.yml`](../config/detekt/detekt.yml) |
| [Android Lint](https://developer.android.com/studio/write/lint) | AGP 8.13.2 | Android-specific correctness: manifest, permissions, API level, resources | [`app/build.gradle.kts`](../app/build.gradle.kts) `lint { }` |

ktlint owns formatting. detekt owns structure. Where they would overlap — line
length — detekt's `MaxLineLength` is switched off so the two never disagree.

## Daily workflow

```bash
./gradlew ktlintFormat    # auto-fix formatting across every module
./gradlew ktlintCheck     # formatting gate only
./gradlew detekt          # code-smell gate only
./gradlew :app:lintDebug  # Android Lint
./gradlew check           # all of the above + tests
```

`ktlintFormat` is safe to run whenever you like; it only changes formatting.

### Pre-commit hook

Checks staged Kotlin against `.editorconfig`. Check-only — it never rewrites your
working tree, so a partial `git add -p` cannot be silently widened.

```bash
./scripts/install-git-hooks.sh    # once per clone
git commit --no-verify            # emergency bypass
```

detekt and Android Lint are not in the hook; they are slower and CI runs them.

## Formatting

`.editorconfig` is the single source of truth. Both the IDE and ktlint read it, so
"Reformat Code" in Android Studio and `ktlintCheck` agree by construction — do not
set formatting preferences in the IDE alone.

- 4-space indent, LF, UTF-8, final newline, 120-column limit.
- Code style is ktlint's `intellij_idea` — what a stock Kotlin IDE produces.
  `ktlint_official` was evaluated and rejected: it splits every expression-body
  function across lines (adding an indent level to the whole body) and squashes
  the aligned trailing comments the codec code uses to document byte layout and
  setup-step numbering. Switching later is a one-line change in `.editorconfig`.
- `no-multi-spaces` is disabled for the same reason: `// m/s`, `// rpm`,
  `// 3000 * sin(-90deg)` and the `// 6 … // 11` setup-step markers in
  `R10Device.runSetup()` are load-bearing, not stray whitespace.
- Composables are PascalCase and JUnit methods are backticked sentences; both are
  declared legal in `.editorconfig` rather than worked around per file.

## Conventions

Not machine-checked. Match the surrounding code.

**Naming.** `camelCase` for functions and properties, `PascalCase` for types and
composables, `SCREAMING_SNAKE_CASE` for constants. Test methods are `camelCase`,
optionally split with underscores into given/when/then segments when a name needs
help — `requestTimeout_leavesCounterUnchanged`, `teeRangeFormulaIsFeetOver3_281`.

**Document the why, not the what.** KDoc on public types and non-obvious public
functions. This repo's protocol code carries the DESIGN.md section reference in the
comment (`§5.1`, `§7.1`, `§8`) — keep that, it is what makes the codec auditable
against the device spec. Do not document getters.

**Wire-format literals get a name when they mean something.** Field widths, opcodes,
and timeouts live in `WireConstants` / `GattUuids`, not inline. detekt's
`MagicNumber` is switched off for `:protocol` because `0xFF` and 255 *are* the
domain there; it stays on for `:app`, where a bare number is usually an accident.

**Coroutines.** Never `GlobalScope` — detekt's `GlobalCoroutineUsage` is on. Pass
the owning `CoroutineScope` explicitly, as `R10Device.pumpAlerts(scope)` does.
Buffer overflow policy on a `SharedFlow` is a deliberate decision with a comment
explaining why `DROP_OLDEST` / `tryEmit` was chosen.

**Errors.** Fail fast and specific in `:protocol`; contain at the boundary in
`:app`. Catching a broad `Exception` around GATT calls is the accepted pattern
(the Android BLE stack throws an undocumented mix), but it must be logged with the
exception and must leave the state machine coherent. Those sites are baselined, so a
*new* broad catch outside them still gets flagged.

**Tests.** Unit tests only, JVM-runnable. `:protocol` must stay free of any
`android.*` / `androidx.*` import — CI enforces this in the `protocol-purity` job.
A test that cannot run without hardware skips cleanly rather than failing.

## detekt policy

`config/detekt/detekt.yml` runs **on top of** detekt's shipped defaults
(`buildUponDefaultConfig = true`) and records only the deltas, each with the
reason. If a rule is not in that file, it runs at the default. Read the file
before arguing with a finding — the deviation may already be deliberate.

### The baseline

`config/detekt/app-baseline.xml` holds findings that predate the gate. A baselined
rule **still fires on new code**; only the exact listed signature is exempt.

- Do not add an entry to make a build go green. Fix it, or record the debt in
  `TODO.md` first and reference it from the baseline comment.
- When you touch a baselined function for real work, clear its baseline entry in
  the same change if you can.

Current baselined debt (tracked as **K5** in [TODO.md](../TODO.md)):

| Rule | Site | Why baselined rather than fixed |
| --- | --- | --- |
| `LongMethod`, `CyclomaticComplexMethod` | `R10ForegroundService.startDevice()` | The §7.1 setup sequencer. Long and branchy by nature; splitting it is a real refactor with reconnect-behaviour risk. |
| `LongMethod` | `SettingsScreen` | A Compose screen rendering every §8 key. Splitting is cosmetic. |
| `TooGenericExceptionCaught` | `BleTransportImpl`, `R10ForegroundService` | Deliberate broad catch at the BLE boundary — see **Errors** above. |
| `ReturnCount` | `ShotCsvStore.decode()` | A row parser with a null-return per malformed field. |

## Android Lint policy

Errors fail the build (`abortOnError = true`); warnings are advisory. Two warnings
are accepted and left visible rather than suppressed:

- `UnusedAttribute` — `usesPermissionFlags="neverForLocation"` in the manifest is
  API 31+ and ignored below it. That is the intent; the attribute must stay.
- `ObsoleteSdkInt` — a pre-O notification-channel guard in
  `R10ForegroundService.createChannel()` left over from an earlier `minSdk`.
  Harmless; worth deleting when that file is next touched.

## Adding a module

Nothing to do. The root build applies ktlint and detekt to `allprojects`, so a new
module inherits the gate. It gets its own detekt baseline at
`config/detekt/<module>-baseline.xml` on the first `./gradlew detektBaseline`
(one file per module, because `detektBaseline` overwrites whatever path it is
given).

## Changing the standard

The standard is code. Change it in a PR that:

1. edits `.editorconfig` and/or `config/detekt/detekt.yml` with the reason in a
   comment,
2. runs `./gradlew ktlintFormat` and, if a rule was newly enabled,
   `./gradlew detektBaseline` — never to absorb a rule you just turned on, only
   genuinely pre-existing hits,
3. shows the resulting diff is green on CI.

A rule change that touches more than a handful of files belongs in its own commit,
separate from any behavioural change, so the reformat does not hide it.

# myR10-Android

![CI](https://github.com/Techdelivery/myR10-Android/actions/workflows/ci.yml/badge.svg)

Garmin R10 Android Application

## Development

- [ROADMAP.md](ROADMAP.md) — **what to build next** and why (start here)
- [DESIGN.md](DESIGN.md) — protocol and architecture
- [HARDWARE_GUIDE.md](HARDWARE_GUIDE.md) — hardware validation runbook
- [TODO.md](TODO.md) — historical M0–M3 execution record and hardware findings
- [docs/CODING_STANDARDS.md](docs/CODING_STANDARDS.md) — the Kotlin coding
  standard and how ktlint, detekt and Android Lint enforce it

```bash
./scripts/install-git-hooks.sh   # once per clone: pre-commit formatting gate
./gradlew ktlintFormat           # auto-fix formatting
./gradlew check                  # style gate + tests
```

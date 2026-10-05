# Changelog (fork)

Changes that exist only in this fork of [Helldez/BigMoeOnEdge](https://github.com/Helldez/BigMoeOnEdge).
Upstream's `CHANGELOG.md` is left alone so that merging `upstream/main` stays conflict-free; engine
changes offered back upstream go there instead, per `AGENTS.md`.

## [0.28.0-chat.1] - 2026-10-04

### Added
- `scripts/build-android.sh`: Linux/macOS port of `build-android.ps1`, with the cmake flags of the
  CI "Cross-compile bmoe-cli (arm64)" step. Falls back to the default generator without `ninja`.

### Changed
- Application id is `io.github.orirune.bmoechat` (dev flavor: `.dev`), so the fork installs next to
  the upstream app. The Kotlin namespace stays `io.bigmoeonedge.example`.
- App name is "BigMoe Chat"; `versionName` is `<upstream version>-chat.<n>`.

# Changelog (fork)

Changes that exist only in this fork of [Helldez/BigMoeOnEdge](https://github.com/Helldez/BigMoeOnEdge).
Upstream's `CHANGELOG.md` is left alone so that merging `upstream/main` stays conflict-free; engine
changes offered back upstream go there instead, per `AGENTS.md`.

## [0.28.0-chat.2] - 2026-10-04

### Added
- Chat foundation (no screens yet): a Room database of conversations and messages
  (`chat/data/`), and an engine service hosted in its own Android process, `:engine`
  (`chat/engine/`). The `bmoe-cli` child then has a memory cgroup of its own instead of sharing the
  UI's, which is what throttled the UI thread into "not responding" when a model filled memory.
  Details in [`examples/android/CHAT.md`](examples/android/CHAT.md).
- A queue with texting semantics: a message is always accepted, a burst gets one reply that reads
  all of it, replies are produced oldest first across conversations, and each job seeds the engine
  with the stored conversation (engine `replace_history`, `fit_ctx`).
- Recovery: a reply left running by a dead engine process is retried once, then fails with a Retry.
- `ChatSettings` (`chat_settings` preferences) and a footprint rule for chat: a model over 6 GiB
  gets a 500 MiB expert cache and a 2048-token context, so it fits the ~3.1 GiB the `:engine`
  process is held to.
- A fake engine and a debug-only broadcast receiver, so the emulator and `adb` can drive the whole
  path without a model.

### Changed
- `app/build.gradle`: KSP, Room 2.6.1, lifecycle-viewmodel-compose, navigation-compose;
  `AndroidManifest.xml`: the `:engine` service and `FOREGROUND_SERVICE_SPECIAL_USE`.

## [0.28.0-chat.1] - 2026-10-04

### Added
- `scripts/build-android.sh`: Linux/macOS port of `build-android.ps1`, with the cmake flags of the
  CI "Cross-compile bmoe-cli (arm64)" step. Falls back to the default generator without `ninja`.

### Changed
- Application id is `io.github.orirune.bmoechat` (dev flavor: `.dev`), so the fork installs next to
  the upstream app. The Kotlin namespace stays `io.bigmoeonedge.example`.
- App name is "BigMoe Chat"; `versionName` is `<upstream version>-chat.<n>`.

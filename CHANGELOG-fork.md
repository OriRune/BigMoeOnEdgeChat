# Changelog (fork)

Changes that exist only in this fork of [Helldez/BigMoeOnEdge](https://github.com/Helldez/BigMoeOnEdge).
Upstream's `CHANGELOG.md` is left alone so that merging `upstream/main` stays conflict-free; engine
changes offered back upstream go there instead, per `AGENTS.md`.

## [0.28.0-chat.5] - 2026-10-04

### Added
- Export a chat as Markdown through the share sheet; change a chat's model from the thread menu;
  the engine banner shows how much memory the loaded model holds. Search over titles and message
  text arrived with the list screen.

### Changed
- `res/xml/file_paths.xml`: one more `cache-path` for the exported files.

## [0.28.0-chat.4] - 2026-10-04

### Added
- Reply notifications (`chat/notify/`): a messaging-style notification per conversation with inline
  Reply, Mark read and Retry, posted by the engine process when the thread is not on screen.
- The engine service's notification shows live progress with a Stop button; the one-time
  notification permission prompt with a reason; a *Show replies on the lock screen* setting.

## [0.28.0-chat.3] - 2026-10-04

### Added
- Chat screens (`chat/ui/`): conversation list with search, new chat, thread with queueing,
  streaming, Stop, Retry, Regenerate, Edit & resend, text-file attachment, and chat settings.
  The launcher is now `ChatActivity`; the previous screen is the **Engine lab**, reached from the
  menu, which pauses the chat queue while it is open.

### Changed
- `AndroidManifest.xml`: `ChatActivity` takes the launcher intent filter; `MainActivity` is labelled
  "Engine lab" and no longer a launcher.

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

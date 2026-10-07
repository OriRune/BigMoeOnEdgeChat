# Changelog (fork)

Changes that exist only in this fork of [Helldez/BigMoeOnEdge](https://github.com/Helldez/BigMoeOnEdge).
Upstream's `CHANGELOG.md` is left alone so that merging `upstream/main` stays conflict-free; engine
changes offered back upstream go there instead, per `AGENTS.md`.

## [0.28.0-chat.9] - 2026-10-06

### Added
- **A scan you can read.** The running scan's card shows *cell N of about M* with a progress bar and
  the time left, the phase (cooling, loading, generating) with how long it has lasted, the model's
  memory while loading, tokens and the current rate while generating, a *last update* line that turns
  into *No progress for N min* with a **Restart scan** button when the executor goes quiet, and a
  checklist of the stages with what each one found. Earlier scans show their date, duration, cell count
  and outcome (and where a stopped one stopped). A scan's page gains a summary card, *How it went* and a
  *Timeline* of every cell with its timings and errors. Everything is derived from the stored cells, so
  the scans already on a phone read the same way.
- Database version 5 (migration tested): the scan's live state (phase, heartbeat, tokens so far).

### Changed
- The scan executor writes a heartbeat from every loop, including the model load, which used to be
  silent for as long as it took. An engine that produces nothing for 15 minutes is stopped and the cell
  recorded as failed instead of being waited on for ever.
- `ScanPlanner.next` is now the first unmeasured cell of `ScanPlanner.plan`, which also describes the
  rest of the scan; the planner's behaviour is unchanged (its tests are).

## [0.28.0-chat.8] - 2026-10-06

### Added
- **Thinking levels.** A chat with thinking on no longer risks spending its whole reply length
  reasoning. The chat's level (Low 256 tokens, the default; Medium 1024; High 4096) is a budget the
  engine enforces: after that many reasoning tokens it ends the reasoning and the model answers. The
  budget comes on top of the reply length. Chosen in *New chat* and in the thread menu; a reply that
  was cut says so in its Thinking block. **Answer now** ends the thinking of the reply being written,
  and a reply that still ends with no answer says why. Needs the engine's `think_budget` and
  `end_thinking` (`CHANGELOG.md`, 0.28.2).
- Database version 4 (migration tested): the thinking level per chat, and per reply whether the
  reasoning was cut and how long it was. Existing thinking chats become Low.

### Changed
- The thinking switch of *New chat* is a four-way choice (Off, Low, Medium, High).

## [0.28.0-chat.7] - 2026-10-06

### Added
- **Foreground mode** per model (thread menu): the model's chats run in a second engine service in the
  main process, which gets about twice the memory of `:engine`, so a large model keeps its full
  footprint and is faster. It works only while the app is in front: leaving the app freezes a running
  reply (`SIGSTOP`) and coming back thaws it, an idle loaded model is unloaded, and replies queue while
  the app is closed. Each service serves only its own models, and a file lock keeps one engine running
  at a time across both processes.

### Changed
- The engine status row is no longer rewritten as idle by a service that has nothing to do.

## [0.28.0-chat.6] - 2026-10-04

### Added
- **Scan** (`scan/`): finds the fastest engine settings for a model on the phone, running in the
  `:engine` process with a wake lock so it completes with the screen off. Burst cells compare
  settings from a cool start, sustained cells measure what a long reply gets once the phone is hot
  and decide the recommendation, a cooldown gate (thermal headroom, status, CPU frequency caps,
  battery temperature, free memory) precedes every cell, and the best result is confirmed against
  the baseline. Lossless by default; lossy settings are opt-in and never applied automatically.
  Results screen, per-model profile used by chats, CSV export, resumable after the process dies.
- Database version 2 (migration tested): scan runs, scan cells and model profiles.
- Fake phone for the emulator: a simulated thermal model and a settings-dependent fake engine speed.

### Changed
- `EngineSession` is shared by the chat job loop and the scan executor.
- Scan: a model load that takes over 45 minutes (thrashing swap) is recorded as failed, a burst cell
  is not re-run after a cooldown gate that gave up (the second attempt would start warm too), and
  opening the app kicks a scan that is marked running.
- Scan: the settings a scan starts from are stored with the run (database version 3, migration
  tested). Before, a resume re-read the global settings, and a change made while the scan was paused
  gave every cell a new identity, so finished cells were measured again.

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

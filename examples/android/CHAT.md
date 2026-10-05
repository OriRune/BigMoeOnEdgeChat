# BigMoe Chat (fork notes)

This fork adds a texting-style chat and a per-model settings scan on top of the upstream example
app. Everything fork-specific is documented here; upstream files are edited only at the seams
listed in the fork plan, so that `git merge upstream/main` stays cheap.

## Identity

| | Upstream | Fork |
|---|---|---|
| Application id | `io.bigmoeonedge.example` | `io.github.orirune.bmoechat` (dev flavor: `.dev`) |
| App name | BigMoeOnEdge | BigMoe Chat |
| Version name | `<upstream version>` | `<upstream version>-chat.<n>` |

The two apps can be installed side by side. Do not leave both with a loaded model: each engine
session holds its own copy of the dense weights and expert cache.

## Building

```bash
export JAVA_HOME=~/android-studio/jbr ANDROID_HOME=~/Android/Sdk
scripts/build-android.sh
cd examples/android && ./gradlew assembleDevDebug testDevDebugUnitTest
```

## How it fits together

```
UI process (main)                          :engine process
  ChatRepository ──► Room (chat.db) ◄────── EngineRunner ◄── EngineService (foreground, wake lock)
  EngineClient ── intents ───────────────────────────────────►        │
                                                                      └─ bmoe-cli --session (child)
```

- **Why a second process.** The lab screen runs the engine as a child of the app process, so the
  engine and the UI share one memory cgroup. When the model fills memory the kernel throttles every
  task in the group, UI thread included, and Android shows "not responding"
  (`mem_cgroup_handle_over_high` in the stored ANR traces). The chat's engine runs in `:engine`, so
  the child lands in that process's cgroup and the UI's stays nearly empty.
- **The database is the interface.** Both processes open `chat.db` (multi-instance invalidation is on, so a
  write in one wakes the other's `Flow`s). The UI never talks to the engine except through intents
  (`EngineClient`: kick, cancel, unload, suspend/resume). `EngineStatusEntity` carries the engine's
  state the other way; `UiPresenceEntity` tells the engine which thread is on screen.
- **Memory budget.** A process held in the background gets ~3.1 GiB of `memory.high`; above it the
  kernel puts the process to sleep (measured: a 35B model with the lab defaults gave no token in
  395 s, and ~0.65 tok/s with a 500 MiB cache and a 2048 context). So for a model file over 6 GiB
  the chat uses `--cache-mb 500 --force-cache` and `-c 2048` unless you set a context yourself
  (`EngineConfig`). Smaller models keep the lab's engine settings.

## Screens

`ChatActivity` is the launcher; the original single screen is kept as the **Engine lab** (menu →
Engine lab) with every engine setting, the metrics and the model downloader. Opening the lab
pauses the chat queue and frees the model (`EngineClient.suspend()`), and returning resumes it.

- **Chats**: title, last message, relative time, model, a status chip (Queued, Writing…, Failed)
  and an unread dot. A banner shows what the engine is doing (loading, writing, paused, error with
  Retry). Long-press a row to rename or delete; the magnifier searches titles and message text.
- **New chat**: pick a model (the last one used is preselected), an optional system prompt and the
  thinking switch. The title is the first 40 characters of the first message until renamed.
- **Thread**: bubbles with the newest at the bottom. A reply that is still being written is plain
  text and becomes Markdown when it is done (re-parsing Markdown per update is what froze the lab
  screen). Reasoning is a collapsible block. States: *Queued · N ahead*, *Reading the conversation…*
  (or the loading text while the model loads), *Writing · x tok/s* with Stop, *Failed* with Retry.
  Long-press a bubble: Copy, Delete, Regenerate (last reply), Edit & resend (last message of
  yours). The composer is never disabled: sending while a reply is writing queues it. *Attach*
  inserts a text file (up to 64 KB) into the box. A divider marks where the model's memory begins
  when older messages no longer fit the context.
- **Settings** (chat settings, with a button to the engine settings of the lab).

## Queue semantics

- Sending is never blocked. A message always lands in the conversation; if the conversation already
  has a reply waiting in the queue, that reply answers the new message as well (consecutive user
  messages are joined with a blank line, because chat templates expect alternating roles).
- A message sent while a reply is being written gets its own reply, queued behind it.
- Replies run oldest first across all conversations. Switching to another conversation's model
  reloads the model.
- Every job re-sends the stored conversation to the engine (`history_roles` / `history_contents`,
  `fit_ctx`). The engine keeps whatever prefix of its KV cache still matches, so a follow-up pays for
  the new turn only. What does not fit the context is dropped from the front by the engine and
  marked `outOfContext` in the database.
- A reply that was running when the engine process died is queued again once; a second death
  marks it failed ("Interrupted") with a Retry.
- Low battery (< 15%, not charging) holds the queue when `pauseOnLowBattery` is on.

## Settings

`ChatSettings` (preferences file `chat_settings`; the UI writes, the engine process reads at the
start of every job): reply length (1024), context (automatic: 4096, or 2048 for a big model),
temperature (0.7, 0 = greedy), top-p (0.9), top-k (40), how long to keep the model loaded (30 min),
pause on low battery. Engine knobs (cache, threads, dense mode...) stay in the lab's settings.

## Driving it from adb (debug builds)

A debug build has a receiver that exercises the queue and the engine process without screens. The
app must be in the foreground when you broadcast (Android refuses to start a foreground service
from a bare background broadcast).

```bash
R=io.github.orirune.bmoechat.dev/io.bigmoeonedge.example.chat.DebugChatReceiver
adb shell am start -n io.github.orirune.bmoechat.dev/io.bigmoeonedge.example.MainActivity
adb shell am broadcast -n $R -a SEND --es text "'hello there'" --es model /sdcard/Download/<model>.gguf
adb shell am broadcast -n $R -a SEND --es text "'and again'" --el conv 1      # same conversation
adb shell am broadcast -n $R -a SEND --es text "'hi'" --ez fake true           # emulator: fake engine
adb shell am broadcast -n $R -a DUMP                                           # then: adb logcat -s BmoeChatDebug
```

The fake engine (debug builds, `fakeEngine` setting) speaks the same protocol. A prompt ending in
`[fail]` or `[crash]` makes it fail; `[slow]`, `[long]` and `[drop2]` change its pace, length and
reported drops.

Changes are logged in [`CHANGELOG-fork.md`](../../CHANGELOG-fork.md).

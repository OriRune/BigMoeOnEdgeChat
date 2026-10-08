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
- **Foreground mode (per model).** Only a process that is `top-app` gets ~6 GiB; `:engine` never does.
  *Thread menu → Foreground mode* runs that model's chats in `ForegroundEngineService`, a second
  engine service in the main process, without the small footprint. The price: it only works while the
  app is in front. Leaving the app freezes the running reply in place (`SIGSTOP` on the engine child;
  memory and progress stay) and coming back continues it; an idle loaded model is unloaded instead;
  a reply frozen for 30 minutes is put back in the queue. Replies for such a model wait in the queue
  while the app is closed (no notification, no inline reply), then run when it opens. Each service only
  serves its own models, and a file lock (`engine.slot`) lets one engine work at a time across the two
  processes, so a scan or a background reply makes a foreground reply wait. Switching the setting
  unloads the model, which then reloads in the other process. The setting is per model and lives in
  the chat settings file (`foregroundModels`).

## Screens

`ChatActivity` is the launcher; the original single screen is kept as the **Engine lab** (menu →
Engine lab) with every engine setting, the metrics and the model downloader. Opening the lab
pauses the chat queue and frees the model (`EngineClient.suspend()`), and returning resumes it.

- **Chats**: title, last message, relative time, model, a status chip (Queued, Writing…, Failed)
  and an unread dot. A banner shows what the engine is doing (loading, writing, paused, error with
  Retry). Long-press a row to rename or delete; the magnifier searches titles and message text.
- **New chat**: pick a model (the last one used is preselected), an optional system prompt and the
  thinking level (Off, Low, Medium or High; see *Thinking* below). The title is the first 40 characters of the first message until renamed.
- **Thread**: bubbles with the newest at the bottom. A reply that is still being written is plain
  text and becomes Markdown when it is done (re-parsing Markdown per update is what froze the lab
  screen). Reasoning is a collapsible block. States: *Queued · N ahead*, *Reading the conversation…*
  (or the loading text while the model loads), *Writing · x tok/s* with Stop, *Failed* with Retry.
  While the model is still thinking, **Answer now** ends the reasoning and makes it answer.
  Long-press a bubble: Copy, Delete, Regenerate (last reply), Edit & resend (last message of
  yours). The composer is never disabled: sending while a reply is writing queues it. *Attach*
  inserts a text file (up to 64 KB) into the box. A divider marks where the model's memory begins
  when older messages no longer fit the context.
- **Thread menu**: Rename, Delete chat, **Thinking** (Off, Low, Medium, High; from the next reply),
  **Change model** (applies from the next reply, which reloads
  the model first if it differs from the loaded one) and **Export as Markdown** (share sheet; the
  file holds the finished turns, with reasoning folded into a `<details>` block).
- **Chats menu**: **Unload model now** frees the memory at once; the banner then reads "Model
  loaded · <name> · 2.4 GB in memory" while a model is held, from the engine child's anonymous
  memory plus swap.
- **Settings** (chat settings, with a button to the engine settings of the lab).

## Notifications

- **Reply**: when a reply finishes (or fails) and its thread is not on screen, the engine process
  posts one messaging-style notification per conversation, updated in place, with the last few
  messages, an inline **Reply** and **Mark read** (or **Retry** after a failure). Replying from the
  notification queues the message and re-posts the notification as "Queued". Tapping opens the
  thread. Opening a thread cancels its notification. Whether a thread is on screen comes from a
  presence row the UI writes in onResume/onPause; a dead UI process counts as not looking.
- **Model activity**: the ongoing notification of the `:engine` service shows what it is doing
  ("Loading…", "Writing reply · 120 tok · 1.7 tok/s", "Model loaded · idle", "Paused — low
  battery") with a Stop button, updated at most every 2 s.
- **Lock screen**: the text of a reply shows on the lock screen (setting *Show replies on the lock
  screen*, on by default). On a phone with a fingerprint or PIN, Android still asks you to
  authenticate before the reply box opens; the app cannot waive that.
- The permission is asked once, with a reason, the first time the app opens. Without it the chat
  works and the reply is waiting when you open the app.

## Scan: the fastest settings for a model

Menu → **Scan**. It runs one model under many engine settings, one at a time, and recommends the
settings chats should use. It runs inside the `:engine` process, because the memory limit and the
CPU placement of that process are part of what is being measured, and it keeps going with the screen
off: the foreground service holds a wake lock for the whole scan, cooldown waits included. Messages
sent meanwhile queue and are answered afterwards. Expect about 40 minutes for a small model and
two to four hours for a large one; the screen shows an estimate before Start. Start it with the
phone cool and **unplugged** (charging heats the phone and every cell records whether it was
charging), then lock the phone.

**Why two kinds of cell.** A phone throttles after a few minutes of decoding (on the test phone it
was already at the LIGHT thermal status in the first second once warm), and replies in a chat are
long. So:

- **Burst** cells (256 tokens from a cool start) compare settings against each other fairly.
- **Sustained** cells keep one configuration generating for 12 minutes (4 to 20 selectable) and
  measure what the last five minutes get once the phone is hot. **The recommendation is chosen on
  this**, so a setting that runs cooler can beat one that is faster for a minute.

**The search.** Start from the lossless baseline (your current engine settings with every lossy knob
off; "your current settings" runs first when they differ). Each stage changes one knob of the best
configuration so far and keeps a candidate only if it beats it by 5%, which favours defaults over
noise: dense weights, expert cache size, threads, I/O lanes, release mmap, row streaming, n-gram
drafting. The stage-C runner-up joins the sustained runs with the burst winner and the baseline. The
best sustained configuration is **confirmed** against the baseline in alternating burst cells
(recommended, baseline, recommended, baseline); a gain under 5% is reported as within noise.

**The cooldown gate.** Before the first cell the phone idles three minutes with the engine unloaded
(up to 15 if it is not cool) and that state is recorded as the reference. Before every cell the scan
waits until the thermal headroom, thermal status, CPU frequency caps, battery temperature and free
memory are back at the reference, polling every 15 s and giving up after 10 minutes (recorded on
the cell). A fixed sleep would turn the matrix into a measurement of run order
(`docs/benchmark-method.md`). A cell that reaches thermal status SEVERE is cancelled and re-run
after cooling, and a burst cell that was throttled for more than a quarter of its run is re-run once
(only if the phone had cooled at its start; after a gate that gave up the second attempt would start
warm too) and, if it still was, compared on its cool-only speed. The phone is never pushed past SEVERE, and
the scan pauses below 20% battery when not charging.

**Lossless first.** The default scan changes only settings that do not change the text. *Include
lossy settings* adds dropping cold experts, substitution and a lower top-k at the end, each measured
against the final lossless recommendation and shown apart with how far its text drifted from the
lossless text. They are never applied automatically; *Use these settings* on such a row is a
deliberate choice.

**What it can read.** Only what the app is allowed to read: the thermal API and its headroom, the
CPU frequency caps, the battery, free memory and the child's `/proc` entries. It cannot read the
memory cgroup counters or `/sys/class/thermal`, so the memory budget for the cache stage is a fixed
assumption (the process's ~3.1 GiB limit minus 500 MB) rather than a reading.

**Results.** Per model: the recommendation and a one-line verdict ("Fastest from cold: X. Fastest once
the phone is hot: Y. Chats use Y."), then the burst, sustained, confirmation and lossy tables with
warnings for cells that started warm, did not fully cool, were throttled, used buffered I/O, were
charging or failed. **Use for chats with this model** saves the settings as that model's profile
(the thread's top bar says *Scan-tuned settings*, and the thread menu resets it). **Export CSV**
shares the cells and the raw samples. A stopped or killed scan keeps its finished cells and resumes
at the first unfinished one; a cell that was running is run again. The settings a scan started from
are stored with the run, so changing the global settings while it is paused does not change its
baseline or make it repeat finished cells.

**Watching a scan.** While it runs the Scan screen shows one card that answers "is it working, and how
far is it":
- *Cell 9 of about 24* with a progress bar and the time left. The plan (`ScanPlanner.plan`) is the same
  code that picks the next cell, so the count is exact up to the first unmeasured cell and a projection
  after it, which assumes nothing wins from here; the confirmation cells (4) only join once a setting
  has won, which the card says ("+4 more if a setting wins"). The time left comes from how long this
  run's cells of each kind have taken (burst, sustained, confirmation), with fallbacks before any has
  finished.
- The phase: *Cooling › Loading › Generating* with the time spent in it. Loading shows how much of the
  model is in memory, which tells a slow load from a hung one; generating shows the tokens so far
  against the target (or the elapsed time of a sustained cell) and the rate over the last half minute.
- *Active · updated 3 s ago*: the executor writes a heartbeat every few seconds from every loop it
  runs (settling, cooling, loading, generating). When it has been silent for two minutes the card says
  *No progress for N min* and offers **Restart scan**, which stops it, waits for the engine process to
  let go and resumes (a dead process is started by the stop). An engine that is alive but produces
  nothing for 15 minutes is stopped by the scan itself and the cell is recorded as failed, so a hung
  child cannot stall it for ever.
- A checklist of the stages (✓ done, ▶ now, ○ waiting, – not applicable to this model), each with what
  it found: *C · Threads: kept the baseline (best alternative +4%, needs +5%)*, *B · Expert cache:
  cache 3000 MiB won (+6%)*.

**History.** *Earlier scans* lists each run with its date, how long it took, how many cells it measured
and its outcome, and for a stopped or failed one the cell it stopped at. A scan's page opens with a
summary (status, start time, duration, the recommendation and its gain over the baseline, how many
cells have warnings), then *How it went* (the same checklist, final), a *Timeline* of every cell in
the order it ran (start time, how long it took, how long it waited to cool, load time, result and, for
a failed cell, the error) and finally the sorted measurement tables. Both are derived from the stored
cells, so scans made before this existed read the same way.

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

## Thinking

Qwen3.x and Gemma 4 read one on/off flag in their chat templates, so there is no "low effort" to ask
for, and a model that thinks at under one token a second can spend a whole reply budget (and half an
hour) inside its reasoning without ever answering. A chat's thinking level is therefore a token budget
the engine enforces: after that many tokens of reasoning it writes the span's closing tag itself and
the model answers from there (`think_budget`, see `docs/session.md`). Low is 256 tokens, Medium 1024,
High 4096, and Low is the default for a chat that has thinking on. The budget comes on top of the reply
length, so a long think cannot eat the answer; on a small context it is capped at a quarter of it
(`EngineConfig.turnBudget`). A reply that was cut says so in its Thinking block ("cut at N tokens").
**Answer now** makes the same cut by hand. A model whose template declares no reasoning span (LFM2.5,
for one) is left alone, and its reply simply runs to the reply length.

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
adb shell am broadcast -n $R -a FOREGROUND --es model /sdcard/Download/<model>.gguf --ez on true
adb shell am broadcast -n $R -a DUMP                                           # then: adb logcat -s BmoeChatDebug
```

The fake engine (debug builds, `fakeEngine` setting) speaks the same protocol. A prompt ending in
`[fail]` or `[crash]` makes it fail; `[slow]`, `[long]` and `[drop2]` change its pace, length and
reported drops; `[thinklong]` gives a thinking chat a 600-token reasoning span, which the thinking
budget or *Answer now* cuts short.

Changes are logged in [`CHANGELOG-fork.md`](../../CHANGELOG-fork.md).

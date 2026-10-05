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

Changes are logged in [`CHANGELOG-fork.md`](../../CHANGELOG-fork.md).

# Blocker — Phase 5a gate needs a physical device

**Status:** halted at the 5a checkpoint per the plan's stop rule ("a gate is
unreachable without human input → write docs/blocker.md and halt").

## What is verified green (emulator, `githubRealDebug`)

- Real binding boots: `RealBindings` resolves the full real graph, zero stub
  types; app cold-starts, Home pulls live YouTube catalog data.
- E2E suite authored + compiling: 4 classes + fixtures + `Phase5bGateE2eTest`
  (2 authored-deferred cases).
- Green on emulator: HostileInput 4/4, Phase5bGate 2/2, QueueReorderResume,
  YouTubeResolvePlay 3/4 (foreign-controller rejection, `file://`/`https://`
  rejection all pass).
- See `docs/e2e/qa-matrix.md` for the per-case map — **7 real bugs were found
  and fixed** by this suite (grant-loss banner gap, queue mirror, revoke
  crash, SAF write-flag crash, nav serialization, tap-to-play, scan summary).

## Why the gate cannot close here

Three independent environment walls, verified — none are app bugs:

1. **The corpus is not on this machine.** The 5a ground truth is 100 discs /
   1669 tracks / 205 JPEGs (~21 GB, "The Piano" collection on the dev device).
   `coldScan` asserts those counts — an emulator with only the E2E fixtures
   (3 discs) cannot pass it, and neither can gapless ±100 ms or the CD38→39
   <150 ms transition gate.
2. **This IP is YouTube-bot-blocked.** Every unauthenticated InnerTube
   `player` call from this VM/emulator returns `playabilityStatus.status =
   LOGIN_REQUIRED` ("Sign in to confirm you're not a bot") — verified with a
   direct curl replaying the app's exact request body. `streamUri` therefore
   fails with `AllClientsFailed` (each client correctly classified
   unplayable). A residential/mobile-IP device will not see this.
3. **Battery/storage gates are device-lab procedures** (30-min drain A/B,
   mid-play `rm -rf` of the cache dir) — meaningless on an emulator.

## What unblocks it

On the dev device (API 27+, USB debugging, corpus copied to storage):

```
adb push corpus → device storage
./gradlew :app:assembleGithubRealDebug :app:assembleFossRealDebug
adb install -r app/build/outputs/apk/githubReal/debug/*.apk
adb install -r app/build/outputs/apk/androidTest/githubReal/debug/*.apk
adb shell am instrument -w \
  com.pianostream.debug.test/androidx.test.runner.AndroidJUnitRunner
# SAF-pick the corpus root once (one-time manual step — grant is interactive)
# then record results into docs/e2e/qa-matrix.md
```

After the device matrix is green → Phase 5b (signing + updater + docs) can
start; `Phase5bGateE2eTest.tamperedApk` auto-activates once `ApkVerifier`
exists (it currently assume-skips).

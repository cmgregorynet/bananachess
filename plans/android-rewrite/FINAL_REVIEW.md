# Final review: android-rewrite

**Date:** 2026-11-23 · **Branch:** `plan/android-rewrite` · **Base:** `main`

## Summary

Banana Chess now runs as a native Android app. It has two modules. `:engine` is a hand port of the Swift engine to plain Kotlin on the JVM. `:app` is a Jetpack Compose app laid out in feature folders.

The app plays local two-player matches, matches against the computer at all four levels, and correspondence matches by link. Links are byte-compatible with iOS in both directions.

A match is saved as it is played and offered on Home as "Resume match". It survives Android killing the app in the background.

The release build is shrunk with R8 and can be signed from properties. With no signing properties it builds unsigned.

All 27 slices are done, for 67 of 67 points. That includes three slices added after the mid-execution architecture review: S25, S26 and S27.

**This branch also carries 4 iOS and engine commits that were already on `layering-pass` but never merged to `main`.** Among them are the Brutal CPU level, "A move waits for the player to come back" and "Views draw; view models decide". The plan branch was cut from `layering-pass`. A pull request into `main` will therefore include that work, and two of the findings below are about it.

## Test results

The run below was from a clean build with `--no-build-cache --rerun-tasks`: 153 tasks, all executed, BUILD SUCCESSFUL.

| Suite | Result |
|---|---|
| `:engine:test` | 441 tests, 0 failures. Includes 108 conformance games (13,829 steps) and 18 golden games (146 samples). |
| `:app:testDebugUnitTest` | 329 tests, 0 failures |
| `:app:testReleaseUnitTest` | 329 tests, 0 failures |
| `:app:lintDebug`, `:app:lintRelease` | clean, with warnings treated as errors |
| `ktlintCheck` | clean |
| `assembleDebug`, `assembleRelease`, `bundleRelease` | built: release APK 1.6 MB, debug 14.8 MB |
| Swift `swift test` (from S24) | 171 tests, passing |

**Emulator smoke test, API 35.** It covered the debug build and the shrunk release APK. The release APK was signed with the local debug key; no keystore is in the repo.
- Home renders.
- Local and computer play work.
- A link written by the iOS engine opens in the right seat.
- After a force-stop, Home offers "Resume match · By link".
- No crashes and no R8 or serialization errors appeared in logcat.

## Findings

The code review ran at `high` effort over `main...plan/android-rewrite`: 206 files, about 33k lines. Each Android finding below was confirmed by reading the code.

### Major — fix before merge

1. **Opening a "Send the final position" link seats the receiver as the sender.** (`Android/app/src/main/kotlin/com/charlesbarton/bananachess/link/LinkPlay.kt:51`)
   - **Cause:** `open` seats the local player as `payload.state.activePlayer`, but a finished match never hands the turn over.
   - **What the player sees:** their opponent labelled "You", the board turned to the wrong side, and the result told from the wrong side. They are also offered "Send the final position" back.
   - **Fix:** for a finished match, seat the player as `lastMove.playedBy.opponent`, falling back to the saved match's seat.
2. **Tapping the same link again is refused with "That link is older than the match on this phone."** (`LinkPlay.kt:137`)
   - **Cause:** `holdsAtLeast` uses `>=`, so a link at the same move counts as stale. The spec copied iOS here, but the message is false, and `start()` already handles an equal saved match by opening it.
   - **Fix:** refuse only when the saved match is strictly further on; an equal link opens the saved match.
3. **The CPU can promote a move on a bound rather than a real score.** (`Engine/Sources/BananaChessEngine/CPU/CPUPlayer.swift:266`, ported to `Android/engine/.../cpu/CpuPlayer.kt:126` and `:148`)
   - **Cause:** root moves are now ordered by the previous depth's scores. `preferringTempo` assumes that any higher-ranked contender was searched first with an exact value. A delivery searched after the best move can fail low and get only an upper bound within `tempoMargin`, and it is then promoted.
   - **Effect:** on Medium it can play a delivery whose fruit then gets blended.
   - **Scope:** this bug came in with `layering-pass`'s Brutal commit, so the Swift engine, which is the spec, has to be fixed first and the fix ported. CPU-driven golden samples may need regenerating.

### Major (iOS, pre-existing on `layering-pass`; not Android code)

4. **iOS can lose a move if the app is killed in the background.** (`App/BananaChess/Match/MatchViewModel.swift:190`)
   - **Cause:** `sceneDidResignActive` cancels the timeline, and `persist()` only runs from `finishTimeline()`. A move made just before leaving the app, or a CPU reply that lands while it's in the background, is not saved before iOS kills the suspended app.
   - **Fix:** Android already avoids this: it persists as soon as the authoritative state changes, in `perform()`. iOS needs the same.

### Minor — follow-up

5. **Changing an option before stored settings load drops the other stored settings for that session.** (`home/HomeViewModel.kt:87`) `hasEdited` skips the whole stored record. This only happens when the first DataStore read is slow. The fix is to merge field by field.
6. **A link match reopened from its saved copy loses the move to animate in its next link.** The snapshot doesn't store `lastMove`, so the opponent sees the result rather than the move played out. iOS never animates link moves at all. The fix is to add `lastMove` to the snapshot.
7. **Taste calls for Charles:**
   - The adaptive icon's banana reads slightly flat at launcher size.
   - Match content is centred vertically, which leaves a large gap under Back and Rules on tall phones.
8. **Device review still open:**
   - Dialog back and outside-tap behaviour is untested.
   - TalkBack can still reach Restart under the result overlay.
   - A computer match has not been played on the release build.
   - Nothing has been run on a physical phone.
9. **Possible test flake:** `ScaffoldTest.skipTheIntroduction` hung once, probably DataStore under Robolectric. It did not reproduce in five reruns.

### Cross-slice integration

- **Seams.** The seams between slices hold. The single construction site for the view model, `newMatchViewModel`, plus the container, the `MatchSource` route, the snapshot store and link play all compose as designed.
- **Duplication.** The duplicate `MatchOptions` was removed in S21, and no other duplicated helpers were found.
- **Engine API.** `GameStateCodec`'s JSON-tree helpers are `internal`, but the object itself stays public.
- **Deliberate differences from iOS.** They are recorded in `DECISIONS.md` → Android, and every one was verified against the code in S24.

## Estimation recap

| | Points |
|---|---|
| Estimated | 67 |
| Actual | 75 (+12%) |

| Slice | Est → Actual | | Slice | Est → Actual |
|---|---|---|---|---|
| S01 toolchain | 3 → 3 | | S15 match VM | 3 → 3 |
| S02 CI | 1 → 1 | | S16 board | 3 → 3 |
| S03 golden fixtures | 2 → 2 | | S17 match screen | 3 → **5** |
| S04 model | 3 → 3 | | S18 effects | 3 → **5** |
| S05 rules | 3 → 2 | | S19 CPU play | 2 → 2 |
| S06 wildcards | 3 → 3 | | S20 resume | 2 → **3** |
| S07 engine tests | 3 → 3 | | S21 link logic | 3 → 3 |
| S08 evaluation | 3 → 2 | | S22 link UI | 2 → **3** |
| S09 CPU | 3 → 3 | | S23 release/icon | 2 → **3** |
| S10 codec | 3 → 3 | | S24 docs | 1 → **2** |
| S11 design system | 3 → 3 | | S25 CPU-dependent tests | 1 → 1 |
| S12 pieces | 3 → 3 | | S26 container | 2 → **3** |
| S13 home | 3 → 3 | | S27 overlay/stability | 2 → **3** |
| S14 rules screen | 2 → 2 | | | |

**Where the estimates drifted:**
- **The engine port was accurate or came in under.** The conformance fixture made a hand port mechanical.
- **Every overrun was in the Android UI layer.** The time went on testing infrastructure rather than production code: Robolectric and Compose test-rule deadlocks, timeouts on long sequences, and DataStore under the test dispatcher.
- **Lesson for the estimator:** on Compose and Robolectric slices, add a point whenever the slice needs a new kind of test harness.

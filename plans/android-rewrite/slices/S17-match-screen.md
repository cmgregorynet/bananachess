---
id: S17
title: Build the Match screen and make local two-player playable end to end
layer: ui
points: 3
depends_on: [S16, S14]
touches: [Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/MatchScreen.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/ScoreboardView.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/WildcardTrackerView.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/ResultOverlay.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/MatchDialogs.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/BananaChessApp.kt, Android/app/src/test/kotlin/com/charlesbarton/bananachess/match/MatchScreenTest.kt]
status: todo
branch:
started:
completed:
actual_points:
review_fixes:
blocked_reason:
---

# S17 — Build the Match screen and make local two-player playable end to end

## Goal
A complete local match on a phone: start from Home, play, see the result, play again.

## Context
- iOS sources: `App/BananaChess/Match/MatchView.swift` (378 lines), `App/BananaChess/Match/WildcardTrackerView.swift` (128 lines). State and actions come from S15's `MatchViewModel`; the board is S16's `BoardView`; the teaching overlay is S14's.
- Layout top to bottom (`MatchView.swift:19-29`): top scoreboard, turn indicator (`testTag` `turnIndicator`, announced politely as a live region), board with 10 dp padding, bottom scoreboard, controls. Top bar: back, and a "Rules" action.
- **Scoreboard** (`:240-295`): name, mini fruit icons at 18 dp, then "· last fruit — ends the match" (amber) or "· N pts on the board"; score in serif 30 sp; washi panel tinted `Palette.panelTint(seat)` at 0.65 with an amber border when that side is to move; the wildcard tracker when wildcards are on. Read by TalkBack as "<name>, score S, N fruit left[, to move]".
- **WildcardTrackerView**: capsule tracks with 9 sp labels: "Smoothie" (banana, strawberry, pineapple icons at 15 dp, greyed to 0.22 until collected), "Split" (three banana icons), and "Blenderama" or "Countered" as "X/Y", shown only once own fruit has been blended. Complete = amber 1.6 border and cream fill; blocked = struck through at 0.42 alpha. One combined description: "Smoothie X of 3[, already poured], Banana Split X of 3[, no longer possible][, Blenderama X of Y[, countered|, no longer possible]]".
- **Controls** (`:213-236`): "Restart"; in debug builds only, "Undo" and a `t<turnNumber>` label.
- **Dialogs** (`:78-113`): "Restart this match?" — "Restart" / "Keep playing" — "The current board and both scores will be cleared."; the self-blend prompt — "Blend it" / "Pick something else".
- **Result overlay** (`:297-346`): ink 0.28 scrim, result text and detail, both scores, "Play again" and "Done" (returns Home).
- **Lifecycle**: when the screen stops being resumed call `onSceneInactive()`, and `onSceneActive()` when it returns (`MatchView.swift:71-77`), using the lifecycle-aware Compose effect.
- **Teaching overlay**: shown on the first Match screen ever opened, when `hasSeenTeachingOverlay` is false; dismissing it sets the flag.
- **Error surface**: `MatchUiState.errorMessage` shows as a dismissible banner under the turn indicator in every match state (playing, resolving, finished). It never blocks the board.
- Replace the Match placeholder route in `BananaChessApp.kt`: Home's "Local Two Player" starts a `LocalVersus` match built from the current options.
- Large font sizes: the scoreboards and controls wrap or scroll; the board keeps its size.

### Toolchain facts established by S01 (read before writing code)
- Versions are pinned in `Android/gradle/libs.versions.toml`: Gradle 9.9.2, AGP 9.5.2 (built-in Kotlin, so `:app` has no `kotlin-android` plugin), Kotlin 2.4.21, compileSdk 37.2, targetSdk 37, minSdk 26, Compose BOM 2026.11.01.
- `:engine` tests use JUnit Jupiter 6 with `kotlin.test`. `:app` tests use JUnit 4 with Robolectric 4.18.1, which simulates **API 35** (`Android/app/src/test/resources/robolectric.properties`).
- Compose UI tests use the **v2** rule: `androidx.compose.ui.test.junit4.v2.createAndroidComposeRule` (v1 is deprecated and warns).
- ktlint uses the `ktlint_official` style: in `.kt` files a chain of four or more calls is wrapped one call per line; `@Composable` functions are exempt from function naming. Run `./gradlew ktlintFormat` before checking.
- Lint treats warnings as errors.
- The build cache is on. To be sure tests really ran, add `--no-build-cache --rerun-tasks` to the Verification command at least once before reporting.
- Machine: `platforms;android-37.0`, `platforms;android-37.2`, `build-tools;37.0.0` and `platform-tools` are installed. Do not install or remove SDK packages.

### Common context (the same in every slice of this plan)
- Read `plans/android-rewrite/RESEARCH.md` first. It maps the existing code and lists the exactness rules.
- This repo holds one game three times: the Swift engine in `Engine/` (**the specification**), the iOS app in `App/`, and a TypeScript port in `Web/src/engine/` (a useful second reference that already passes the fixture). This plan adds a fourth: a native Android app in `Android/`.
- Gradle modules: `:engine` is pure Kotlin/JVM, package `com.charlesbarton.bananachess.engine`, sources under `Android/engine/src/main/kotlin/com/charlesbarton/bananachess/engine/`, and must never import `android.*` or `androidx.*`. `:app` is the Compose app, package `com.charlesbarton.bananachess`, sources under `Android/app/src/main/kotlin/com/charlesbarton/bananachess/`, in feature-first folders (`art/`, `home/`, `match/`, `rules/`, `link/`, `settings/`).
- Environment: run `source Android/scripts/env.sh` before any Gradle command. It exports `JAVA_HOME` and `ANDROID_HOME`. Run Gradle from `Android/` with `./gradlew`.
- Conventions: immutable `data class` values and `sealed interface` hierarchies (the Swift code relies on value semantics; never share a mutable list between states); ViewModels own all observable state and expose it as `StateFlow`; constructor injection with a default production instance; no DI container; no repository wrappers; an interface only when a test needs a fake; dispatchers are injected and named in code, never implied; no `!!` on production paths; comments only where the reason is not obvious; one error type per layer with user-readable messages.
- Dependencies: every library is already declared in `Android/gradle/libs.versions.toml` by S01. Do not add one. If the slice cannot be done without a new dependency, stop and report it.
- Do not edit `plans/`, `App/`, `Web/` or `Engine/` unless this slice's Scope says so.
- User-facing copy is ported verbatim from the iOS source unless this slice says otherwise.

## Scope
- `MatchScreen`, `ScoreboardView`, `WildcardTrackerView`, `ResultOverlay`, `MatchDialogs`, the error banner.
- Navigation wiring for local matches and the "Rules" action.
- Robolectric Compose smoke tests, ported from `App/BananaChessUITests/MatchSmokeUITests.swift`: select, deselect and move a fruit, after which the turn indicator names Player Two; a blender shows a diagonal legal move onto its own fruit containing "minus"; self-blend shows "Blend it" and the score then reads "score -1"; the waiting side cannot select; no tracker in a standard match and exactly two in a wildcard match, the first containing "Banana Split 0 of 3"; the self-blend dialog mentions "Blenderama" only with wildcards on; Blendy puts a banana on (5,0) and a blender on (4,1); a set `errorMessage` is visible and dismissible.
- A played-out test: drive a full local match to the result overlay by always taking the first legal action, then "Play again" starts a fresh board.

## Out of Scope
- CPU matches (S19).
- Resume (S20).
- Link play (S21, S22).
- Burst, splatter and haptics (S18).

## Acceptance Criteria
- [ ] A local match can be started from Home, played to a result and restarted, in a single Compose test.
- [ ] The error banner is shown when `errorMessage` is set while a match is in progress and while the result overlay is up.
- [ ] Leaving and returning to the screen pauses and resumes the timeline (asserted with a lifecycle-driven test).
- [ ] Tests added; build, lint and existing tests green.

## Verification
```bash
source Android/scripts/env.sh
cd Android && ./gradlew :app:testDebugUnitTest :app:lintDebug ktlintCheck :app:assembleDebug
```

## Estimate Rationale
3: several components and the end-to-end smoke tests.

## Actuals
- **Actual points:**
- **What took longer / shorter than expected:**
- **Surprises / unknowns that surfaced:**
- **Review fixes applied:**

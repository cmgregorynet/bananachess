---
id: S15
title: Build the match view model: selection, moves, the event timeline and board accessibility text
layer: ui
points: 3
depends_on: [S06, S13]
touches: [Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/MatchViewModel.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/MatchUiState.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/EventTimeline.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/SquareDescription.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/BlendEffectPolicy.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/MatchHaptics.kt, Android/app/src/test/kotlin/com/charlesbarton/bananachess/match/]
status: done
branch: slice/android-rewrite/S15
started: 2026-11-23T13:20
completed: 2026-11-23T13:31
actual_points: 3
review_fixes: timeline job launched lazily so pause cannot miss a re-entrant start
blocked_reason:
---

# S15 — Build the match view model: selection, moves, the event timeline and board accessibility text

## Goal
All match behaviour for local two-player play with no UI: taps become moves, events play back in order with the iOS timings, and every square can describe itself.

## Context
- iOS source: `App/BananaChess/Match/MatchViewModel.swift` (942 lines; this slice covers everything except CPU `:529-560`, online `:564-698` and link `:707-720`), plus `App/BananaChess/Match/BlendEffectPolicy.swift`.
- **Two states**: `authoritativeState` (the engine's truth) and `displayState` (what is on screen while events animate). The UI never computes a rule; it replays events through `GameState.applying(event)`.
- **Tap** (`:205-236`), one handler per square, in this order: (1) a piece is selected and the square is a legal destination → commit or confirm; a legal destination wins over re-selecting; (2) the square is one of the active player's spawns with a buy-back on offer → commit the restore; (3) the active player's own piece → select or toggle. Selection computes destinations from `RulesEngine.legalMoves(for piece)`.
- **Self-blend** (`:240-266`): a move onto the mover's own fruit sets `pendingSelfBlend` instead of committing. Prompt "Blend your own <kind>?"; detail "It leaves the board and costs you N point(s)." plus, with wildcards on, one of: " It is also your last one — this wins the match by Blenderama." / " Blenderama: X of Y once this is done." / " Blenderama is already off for this match, so this is points down and nothing else." (`:40-57`).
- **Timeline** (`:340-433`): events are queued; one coroutine pops them one at a time, applies each to `displayState`, updates the flash, fires the haptic hook, then delays for the beat. Beats (ms): moved 280, blenderDestroyed 480, restored/bananaReturned/respawned 380, pineappleTurnedGolden 620, smoothie/smoothiePoured 620, blenderBattle 900, blended 420, delivered 320, scoreChanged 120, other 60; every beat is 90 under reduced motion. When the queue empties: display = authoritative, `isResolving = false`, flash cleared. After a `blenderBattle`, the following `blenderDestroyed` keeps the battle text.
- **Flash texts** (`:487-525`): "×2  +N"; "+N"; "Own goal!  −N"; "Battle!  A v B  (rolled x and y)" from the bottom player's point of view; "Blender destroyed"; "Blender back · −3"; "Blender back"; "Golden Pineapple!"; "Tropical Smoothie!".
- **Effects as data** (`:440-485`): `blended` adds a `BlendBurst(kind, position, seed)` and, if `policy.roll() < policy.splatterChance`, a `Splatter`; `blenderDestroyed` adds a blender burst and never a splatter. `BlendEffectPolicy`: default chance 0.3 with a random roll; `Always`; `Never`. Effect dice never touch the engine. Views are S18.
- **Pause** (`:181-193`, `:347-350`): `onSceneInactive()` stops the timeline between events (an event is either fully shown or still queued); `onSceneActive()` resumes it. Nothing is lost while the app is in the background.
- **Input lock** (`:197-203`): while resolving, after the match ends, or when this device does not control the active player.
- **Other**: restart (keeps options; Random deals a new board; clears selection and effects) behind `isShowingRestartConfirmation`; undo stack (debug builds only); turn indicator text ("Match over" / "<name> to move"); result text ("A draw — nothing left to blend." or "<name> <reason headline>." with details "Every banana across. The score did not matter." / "Every one of their own fruit, through their own blenders."); scoreboard model (name, fruit left, "· last fruit — ends the match" or "· N pts on the board", score); `isRotated` when the bottom player is player two.
- **Square descriptions** (`:886-930`), 1-based rows and columns: "Empty square, row R, column C" with optional ", blender home" / ", a destroyed blender can come back here, tap to buy it back for 3 points"; "<owner> blender, row…"; "<owner> [golden ]<kind>, row…, value V"; legal suffixes ", legal move", ", legal move, blends your own <kind>, minus V", ", legal move, blends for plus V".
- **Errors**: a `RulesError` from `apply` (should be unreachable through the UI) sets `errorMessage` to the error's message; `dismissError()` clears it. S17 shows it.
- Time: inject the coroutine dispatcher/scope and a `reduceMotion: () -> Boolean`, so tests run on virtual time. `MatchHaptics` is an interface with a no-op default; S18 supplies the real one.
- Constructor takes `MatchMode`, the initial `GameState`, `BlendEffectPolicy`, haptics. Only `LocalVersus` behaviour is required here; write mode checks so `Computer` and `ByLink` can be added in S19 and S21.

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
- `MatchUiState` (immutable) and `MatchViewModel` exposing it as `StateFlow`.
- `EventTimeline` (queue, beats, pause/resume), `SquareDescription`, `BlendEffectPolicy`, `MatchHaptics`.
- Tests on virtual time: select / deselect / move; a legal destination wins over re-selection; self-blend asks first and scores −N after confirmation, with the Blenderama sentence only when wildcards are on; buy-back by tapping a spawn; input is locked while resolving and for the waiting side; events appear on `displayState` in order at the listed beats; reduced motion uses 90 ms; pause holds the queue and resume drains it; display equals authoritative after the timeline; restart resets; an engine error sets `errorMessage`; square descriptions for each case above.

## Out of Scope
- Composables (S16, S17).
- CPU turns (S19).
- Snapshots (S20).
- Link play (S21).
- Effect drawing and real haptics (S18).

## Acceptance Criteria
- [ ] At least 25 view model tests pass on virtual time with no real delays.
- [ ] No test or production code in this slice reads the wall clock or uses `Dispatchers.Main` directly.
- [ ] Tests added; build, lint and existing tests green.

## Verification
```bash
source Android/scripts/env.sh
cd Android && ./gradlew :app:testDebugUnitTest :app:lintDebug ktlintCheck :app:assembleDebug
```

## Estimate Rationale
3: the densest behaviour in the app, but pure state and coroutines on virtual time with no rendering. Prior domain slices ran slightly under estimate (9 estimated, 8 actual).

## Actuals
- **Actual points:** 3. The estimate was 3.
- **What took longer / shorter than expected:** Everything compiled and passed first time; the engine API mapped one to one. Reading the later slices to decide what state lives where took the time.
- **Surprises / unknowns that surfaced:**
  - Deliberate differences from iOS: the "Tropical Smoothie!" label reads blender positions before the event is applied (on iOS it always lands on row 0, column 0); taps are ignored while a self-blend prompt is open; events handed to a running timeline join the queue where iOS cancels and restarts; restart and undo also clear a pending self-blend.
  - `Dispatchers.Main.immediate` appears once, as the default of the injected `scope` constructor parameter (the house pattern from `HomeViewModel`). No code body hops to it.
  - Square descriptions include two iOS phrases the slice text did not list (smoothie-destroyed blender countdown; "knocks both blenders out").
  - The timeline's fix for re-entrant starts on an immediate dispatcher is covered by reasoning and a standard-dispatcher test, not reproduced under an immediate dispatcher.
- **Review fixes applied:** The timeline's job is now launched lazily, stored, then started, so a pause cannot miss a job started re-entrantly.

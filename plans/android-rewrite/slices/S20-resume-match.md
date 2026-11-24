---
id: S20
title: Save the match in progress and offer to resume it
layer: ui
points: 2
depends_on: [S19, S10, S26]
touches: [Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/MatchSnapshotStore.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/MatchViewModel.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/MatchMode.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/home/, Android/app/src/main/kotlin/com/charlesbarton/bananachess/BananaChessApp.kt, Android/app/src/test/kotlin/com/charlesbarton/bananachess/match/MatchSnapshotStoreTest.kt, Android/app/src/test/kotlin/com/charlesbarton/bananachess/home/ResumeTest.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/AppContainer.kt, Android/engine/src/main/kotlin/com/charlesbarton/bananachess/engine/online/GameStateCodec.kt]
status: todo
branch:
started:
completed:
actual_points:
review_fixes:
blocked_reason:
---

# S20 — Save the match in progress and offer to resume it

## Goal
Closing the app mid-match no longer loses it. (iOS saves a snapshot but never reads it back; Android does both.)

## Context
- iOS source: `App/BananaChess/Match/MatchSnapshotStore.swift` (45 lines) and the persist call in `App/BananaChess/Match/MatchViewModel.swift:934-941`.
- Snapshot: `{rulesVersion, mode, state}` as JSON in the app's private files directory (`current-match.json`), written atomically (write a temp file, then rename) on an injected IO dispatcher. Reuse the engine's `GameState` JSON encoding from S10 (`engine/online/`); add a serializer for `MatchMode` here.
- Save after every resolved move and after restart; clear when a match ends or the player chooses "Done" on the result overlay.
- Load on Home: if a snapshot exists with the current `rulesVersion` and the match is not over, Home shows a "Resume match" button above the menu with a one-line detail ("Local two player" / "<Difficulty> CPU" / "By link"), which opens the match exactly where it was. Starting any new match replaces the snapshot.
- A snapshot with a different rules version is deleted, never migrated (`DECISIONS.md`: "Old matches are refused rather than migrated").
- **Errors are deliberately silent here**: saving and loading are best-effort; a failed or unreadable snapshot simply means no "Resume match" button. Catch the specific IO and serialization exceptions, delete the bad file, and leave a comment saying why this is not surfaced.
- A resumed CPU match whose turn belongs to the CPU starts thinking on entry.
- **Established by S10 (read `Android/engine/src/main/kotlin/com/charlesbarton/bananachess/engine/online/`):** `GameStateCodec.encode/decode/toJson/fromJson` (throws `MatchCodecError.Malformed`); `MatchCodec`; `MatchLink.url(payload): String` and `MatchLink.payload(url: String)` (throws `MatchLinkError` or `MatchCodecError`); `MatchPayload(matchId, moveNumber, state, lastMove)` with `LastMove(action, playedBy, events)`; `MatchAuthority(payload, localPlayer)` is **immutable** — `prepareTurn(action)` returns `PreparedTurn(payload, events, expectedPredecessor)`, and `commit`/`adopt` return a new authority that you must keep. The engine's own error messages differ slightly from the wording in this slice; where this slice gives player-facing wording, map to it in `:app`. `MatchOptions` exists in both `engine/online/` and `:app` `match/MatchMode.kt`: in S21, delete the `:app` copy and use the engine's.
- **Established by S15 (read every file in `Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/` and `Android/app/src/test/kotlin/com/charlesbarton/bananachess/match/MatchTestSupport.kt` first):** `MatchViewModel(mode, initialState, blendEffects, haptics, reduceMotion, scope)` exposes `state: StateFlow<MatchUiState>` and `tap(position)`, `confirmSelfBlend()`, `cancelSelfBlend()`, `dismissError()`, `onSceneInactive()`, `onSceneActive()`, `retireBurst(id)`, `retireSplatter(id)`, `requestRestart()`, `cancelRestart()`, `restart()`, `undo()`, and internal `perform(action)`. `MatchUiState` carries everything a view needs as derived properties (`isRotated`, `seat(player)`, `scoreboard(player)`, `turnIndicatorText`, `resultText`, `resultDetail`, `piecesOnBoard`, `blenderHomes`, `purchasableSpawns`, `blendersAwaitingRespawn`, `deliveryEdge(position)`, `selectedSquare`, `legalDestinations`, `flash`, `blendBursts`, `splatter`, `pendingSelfBlend`, `errorMessage`, `canUndo`, `canPlayAgain`) and `squareDescription(position)`. Views draw; the view model decides. Extension points: CPU turns start at the end of the private `finishTimeline()`, in `restart()` and at construction, and reply through `perform(action)`; snapshots save at the same places; an incoming link move sets `displayState` to the position before it and calls the private `animate(events)`.
- **Established by S17 (read `match/MatchScreen.kt`, `ResultOverlay.kt`, `MatchDialogs.kt`, `BananaChessApp.kt`, and `test/.../match/MatchScreenTest.kt`):** `MatchScreen(viewModel, settings, onExit, onShowRules, modifier, showsDebugControls)`. The one production construction site of the view model is `newMatchViewModel(start, reduceMotion)` in `BananaChessApp.kt`; it is scoped to the Match back-stack entry, so changing `graph.matchStart.current` while a match is showing does not swap the match (pop and re-navigate instead). Commented plug-in spots exist in `MatchScreen` for the splatter (S18), the thinking indicator (S19) and the send button (S22). Once the match is over the result scrim swallows taps beneath it; anything that must stay usable then goes in `ResultOverlay`'s `notice` slot. `PaperAlertDialog(title, message, confirmLabel, onConfirm, onDismiss, dismissLabel = null)` is a one-button alert. `MatchTags.TURN_INDICATOR`, `ERROR_BANNER`, `RESULT`.
- **Keep Compose tests fast:** `composeRule.waitForIdle()` after every move of a whole match took over three minutes under load and timed out. Drive long sequences through the view model with `scope.settle()` and call `waitForIdle()` once at the end, as `MatchScreenTest`'s played-out test now does.
- **Changed by the architecture review (findings M1 and S6):** S26 made `Route.Match` carry a `MatchSource` with a reserved `Saved` case and put app-wide objects in an `AppContainer` on the `Application`. In this slice: the snapshot store lives in the container; "Resume match" navigates with `MatchSource.Saved`; and after process death any restored Match route prefers the saved snapshot (so a match in progress comes back exactly where it was, not from its opening). For JSON, use `GameStateCodec.encode/decode` strings; first make `GameStateCodec.toJson/fromJson` `internal` (they expose kotlinx-serialization types through `:engine`'s public API while that dependency is `implementation`).

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
- `MatchSnapshotStore` (interface, file implementation, in-memory fake).
- Persist hooks in `MatchViewModel`; "Resume match" on Home via `HomeViewModel`; navigation wiring.
- Tests: save → load round-trips mode and state for `LocalVersus` and `Computer`; a wrong rules version is dropped and the file removed; a corrupt file is dropped; a finished match leaves no snapshot; Home shows the button only when a snapshot exists; resuming restores the board, scores and side to move.

## Out of Scope
- Saving more than one match.
- Link matches' own bookkeeping (S21).

## Acceptance Criteria
- [ ] A match interrupted after three moves resumes with identical pieces, scores, turn number and active player in a test.
- [ ] No snapshot failure can crash the app or block Home (asserted with a corrupt file and with an unwritable directory fake).
- [ ] Tests added; build, lint and existing tests green.

## Verification
```bash
source Android/scripts/env.sh
cd Android && ./gradlew :app:testDebugUnitTest :app:lintDebug ktlintCheck :app:assembleDebug
```

## Estimate Rationale
2: one file store and one Home entry.

## Actuals
- **Actual points:**
- **What took longer / shorter than expected:**
- **Surprises / unknowns that surfaced:**
- **Review fixes applied:**

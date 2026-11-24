---
id: S27
title: Move the teaching overlay into the view model and make the match screen recompose narrowly
layer: ui
points: 2
depends_on: [S26]
touches: [Android/app/src/main/kotlin/com/charlesbarton/bananachess/rules/TeachingOverlay.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/MatchScreen.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/MatchViewModel.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/MatchUiState.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/BoardView.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/BananaChessApp.kt, Android/app/compose-stability.conf, Android/app/build.gradle.kts, Android/app/src/test/kotlin/com/charlesbarton/bananachess/match/, Android/app/src/test/kotlin/com/charlesbarton/bananachess/rules/]
status: todo
branch:
started:
completed:
actual_points:
review_fixes:
blocked_reason:
---

# S27 — Move the teaching overlay into the view model and make the match screen recompose narrowly

## Goal
Close two architecture-review findings: a composable reads and writes settings directly (state flowing up from the UI), and every match-state change recomposes the whole screen and recomputes all 36 accessibility descriptions.

## Context
- Added during execution on 2026-11-23 after an independent architecture review (findings S1 and S2).
- S1: `FirstMatchTeachingOverlay(settings, …)` in `rules/TeachingOverlay.kt` collects `SettingsStore`, writes `hasSeenTeachingOverlay` from a `rememberCoroutineScope`, and reports visibility upward through `onShowingChange` so `MatchScreen` can hide the board from TalkBack. `MatchScreen` takes a `settings` parameter only for this.
- S2: engine types come from `:engine`, which has no Compose compiler, so Compose treats them as unstable and compares them by identity. `MatchUiState` is a new instance on every flash, burst or selection, so `MatchScreen` and `BoardView` recompose broadly; `remember(state)` in `BoardView`'s squares recomputes all 36 descriptions (each calling `RulesEngine.legalActions`) on every change; `Scoreboard` values are rebuilt every recomposition. It is not janky today; it is the wrong shape as effects and CPU play land. Piece movement is already read in layout through lambdas — keep that.
- **Established by S26 (read `AppContainer.kt`, `BananaChessApplication.kt`, `match/MatchSource.kt`, `BananaChessApp.kt`, and the process-death tests in `test/.../match/MatchScreenTest.kt` and `AppContainerTest.kt`):** app-wide objects live in `AppContainer(settings, dispatchers: AppDispatchers(main, default, io), ioScope)` built once per process by `AppContainer.create(context)`; tests build `AppContainer(InMemorySettingsStore(...))`. The Match route is `Route.Match(source: MatchSource)`; `MatchSource.New(kind, ruleSet, boardSetup, boardWrap, blenderBattles, seed)` deals deterministically; `MatchSource.Saved` is reserved and currently returns Home. `matchStartOf(source)` in `BananaChessApp.kt` resolves a source; `newMatchViewModel(container, start, reduceMotion)` is the single construction site. Process death is simulated in tests by `restartTheProcess()`.

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
- `MatchUiState.isTeachingOverlayShowing`; `MatchViewModel` reads the flag from the settings store (inject it; the container from S26 provides it) and exposes `dismissTeachingOverlay()`, which hides it and records it as seen. `TeachingOverlay` becomes a pure composable (`onFinished`); `FirstMatchTeachingOverlay` and the `settings` parameter of `MatchScreen` go. Back closes the overlay through the view model.
- A Compose compiler stability configuration file declaring `com.charlesbarton.bananachess.engine.model.*` and `com.charlesbarton.bananachess.engine.cpu.CpuDifficulty` stable (they are immutable), wired in `:app`'s build file.
- Narrow the `remember` keys for square descriptions to what they depend on (display state, legal destinations, selected piece, mode), and pass `BoardView` only the values it draws rather than the whole `MatchUiState` where that is practical.
- Tests: the overlay shows for a first-ever match, `Play` records it as seen and it does not show again, back closes it, TalkBack cannot reach the board while it shows; a recomposition-count test (or a description-computation counter) showing a flash change does not recompute the 36 square descriptions.

## Out of Scope
- Any visual change.
- Baseline profiles (S23 may look at them).

## Acceptance Criteria
- [ ] No composable in `:app` takes `SettingsStore` as a parameter or collects it, except Home's existing view-model-backed screen (grep shows it only in view models and the container).
- [ ] A flash or burst change does not recompute square descriptions (asserted).
- [ ] Overlay behaviour from S14/S17 is unchanged for the player (existing tests still pass, adapted to the new API).
- [ ] Tests added; build, lint and existing tests green.

## Verification
```bash
source Android/scripts/env.sh
cd Android && ./gradlew :app:testDebugUnitTest :app:lintDebug ktlintCheck :app:assembleDebug --no-build-cache --rerun-tasks
cd .. && /usr/bin/grep -rln --include='*.kt' 'SettingsStore' Android/app/src/main/kotlin/com/charlesbarton/bananachess/rules Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/MatchScreen.kt | /usr/bin/grep -c . || true   # expect 0
```

## Estimate Rationale
2: a small state move plus build configuration and narrower keys, with tests for each.

## Actuals
- **Actual points:**
- **What took longer / shorter than expected:**
- **Surprises / unknowns that surfaced:**
- **Review fixes applied:**

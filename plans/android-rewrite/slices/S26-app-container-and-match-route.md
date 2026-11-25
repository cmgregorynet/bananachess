---
id: S26
title: Give the app one container and make the Match route carry its source
layer: infra
points: 2
depends_on: [S18]
touches: [Android/app/src/main/kotlin/com/charlesbarton/bananachess/BananaChessApplication.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/AppContainer.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/BananaChessApp.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/MainActivity.kt, Android/app/src/main/AndroidManifest.xml, Android/app/src/main/kotlin/com/charlesbarton/bananachess/settings/SettingsStore.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/home/, Android/app/src/test/kotlin/com/charlesbarton/bananachess/ScaffoldTest.kt, Android/app/src/test/kotlin/com/charlesbarton/bananachess/AppContainerTest.kt, Android/app/src/test/kotlin/com/charlesbarton/bananachess/match/MatchScreenTest.kt, Android/app/src/test/kotlin/com/charlesbarton/bananachess/home/]
status: done
branch: slice/android-rewrite/S26
started: 2026-11-23T17:26
completed: 2026-11-23T17:54
actual_points: 3
review_fixes: container closes its IO scope (fixed suite deadlock); no defaults in saved route
blocked_reason:
---

# S26 — Give the app one container and make the Match route carry its source

## Goal
Fix the one structural problem an architecture review found before resume (S20) and deep links (S22) build on it: a match is handed to the Match screen only through an in-memory holder, so after process death the screen silently returns Home and the match is lost.

## Context
- Added during execution on 2026-11-23 after an independent architecture review (findings M1 and S5). Charles asked that the architecture meet the standard of a top-tier consumer Android team.
- Today: `BananaChessApp.kt` defines `Route.Home/Match/Rules/Invite` as argument-less `@Serializable data object`s; `AppGraph` is an activity-scoped `ViewModel` holding `settings` and `MatchStartHolder` (`matchStart.current: MatchStart?`); Home sets `matchStart.current` then navigates to `Route.Match`; the Match route reads it and pops to Home when it is null. `DataStoreSettingsStore.get(context)` keeps its own static per-file map. `newMatchViewModel(start, reduceMotion)` is the one production construction site of `MatchViewModel`.
- Android's manual dependency-injection guidance puts an app-wide container on the `Application`. Keep it manual: no Hilt, no DI library (Charles's principles; the review agreed DI frameworks are scale-only here).
- Later slices add to the container: S19 a CPU player factory and a background dispatcher, S20 a match snapshot store. Leave clear places for them.

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
- `BananaChessApplication` (registered in the manifest) owning an `AppContainer` with the settings store (move `DataStoreSettingsStore`'s static map into the container so there is exactly one owner) and the dispatchers the app uses (main, default, io), constructed once per process.
- Delete `AppGraph` and `MatchStartHolder`. `MainActivity` gets the container from the application.
- `Route.Match` becomes `@Serializable data class Match(val source: MatchSource)` where `MatchSource` is a small serializable sealed type: `New(kind)` carrying what is needed to build a fresh match (mode plus options and the seed used for a Random board, so the same board is rebuilt after process death), and `Saved` (reserved for S20: load the saved match). A `ByLink(url)` case is added by S21/S22, not here.
- The Match route builds its view model from the route's `source`, scoped to the back-stack entry as today. After process death the route is restored with its argument and the same match is rebuilt; it no longer pops to Home.
- Home builds a `MatchSource` and navigates with it; `HomeViewModel.startLocal()/startComputer()` return what the route needs.
- Tests: process death (recreate the activity with saved state, or restore the nav back stack) on the Match screen rebuilds a match with the same mode and options, and a Random board is rebuilt identically; rotation still keeps the in-progress match (same view model); the container is created once per process; Home → Match → Back still works.

## Out of Scope
- Saving moves in progress — after process death a *new* match restarts from its opening until S20 adds snapshots (S20 makes `Saved` load the snapshot).
- Deep links (S22).

## Acceptance Criteria
- [ ] `MatchStartHolder` and `AppGraph` no longer exist.
- [ ] A test proves that restoring the Match route after process death shows a match, not Home.
- [ ] There is one owner of app-wide objects, on the `Application`.
- [ ] Tests added; build, lint and existing tests green.

## Verification
```bash
source Android/scripts/env.sh
cd Android && ./gradlew :app:testDebugUnitTest :app:lintDebug ktlintCheck :app:assembleDebug --no-build-cache --rerun-tasks
cd .. && /usr/bin/grep -rn --include='*.kt' -E 'MatchStartHolder|AppGraph' Android/app/src | /usr/bin/grep -c . || true   # expect 0
```

## Estimate Rationale
2: a known pattern (route arguments, an Application container) touching navigation, Home and their tests.

## Actuals
- **Actual points:** 3. The estimate was 2.
- **What took longer / shorter than expected:** Longer: an order-dependent deadlock in the full test suite (a settings write hung for over 18 minutes, only when run after other test classes). Robolectric makes a new `Application` per test, and the previous DataStore's IO scope never ended; the old static per-file map had been masking it. The route change and custom navigation type worked first time.
- **Surprises / unknowns that surfaced:**
  - `AppContainer` owns its IO scope and has `close()`; the `Application` closes it in `onTerminate`, which devices never call and Robolectric calls after each test.
  - `MatchSource.New` carries every field (including the seed) with no defaults, so a later default change cannot alter a saved match. A mutation check (ignoring the seed) made the Random process-death test fail.
  - Process death is simulated by saving the navigation state through a real `Parcel` and rebuilding the app with a fresh container and view-model store.
  - After process death a new match deals its opening again; moves in progress are lost until S20.
  - Snapshot loading is asynchronous, so S20 will likely turn the synchronous route resolution into a loading state.
  - Outside `touches`: the new file `match/MatchSource.kt`.
- **Review fixes applied:** Container closes its IO scope (fixed the deadlock); no defaults on saved route fields; a lint fix in the test harness.

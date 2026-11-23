---
id: S25
title: Port the Swift engine tests that needed the CPU, the evaluation or the codec
layer: test
points: 1
depends_on: [S07, S09, S10]
touches: [Android/engine/src/test/kotlin/com/charlesbarton/bananachess/engine/rulesported/CrossCuttingPortedTests.kt, Android/engine/src/test/kotlin/com/charlesbarton/bananachess/engine/rulesported/BlenderamaDefenceTests.kt]
status: todo
branch:
started:
completed:
actual_points:
review_fixes:
blocked_reason:
---

# S25 — Port the Swift engine tests that needed the CPU, the evaluation or the codec

## Goal
Close the gap S07 reported: ten Swift tests could not be ported when S07 ran because the CPU, the evaluation and the payload codec did not exist in Kotlin yet. They do now.

## Context
- Added during execution on 2026-11-23, after S07's report. S07's acceptance item "both Blenderama defence tests" was not fully met; this slice meets it.
- Swift tests to port, from `Engine/Tests/BananaChessEngineTests/`:
  - `BlenderamaDefenceTests.swift`: `scoringRouteOutranksBlenderApproach` (whole test) and the final `Evaluation.score` comparison of `scoringBeatsHovering` (its rules half is already ported; complete it in place and drop the note in its display name).
  - `SetupAndMovementTests.swift`: `randomBoardsPlayThrough`, `wrappedMatchesTerminate` (CPU), `setupSurvivesEncoding`, `wrapSurvivesEncoding` (codec).
  - `EndStateAndDeterminismTests.swift`: `eventReplayHoldsAcrossAMatch`, `nothingRunsForever` (CPU), `codableRoundTrip`, `encodingIsStable` (codec).
- Kotlin pieces now available: `engine/cpu/Evaluation.kt` (`Evaluation.score`, internal, visible to tests), `engine/cpu/CpuPlayer.kt` (S09; inject the seed and a fake clock so these tests do not depend on wall-clock time), `engine/online/` (S10: payload codec and state encoding), and the test support `support/BoardBuilder.kt` with `withOwnFruitBlended` (S07). Swift's `playOut` helper (CPU versus CPU, 600-turn limit) is in `TestSupport.swift`; S09 may already have a Kotlin equivalent in its tests — reuse it if so.
- Follow the layout S07 used: Swift function name as the Kotlin test name, Swift title as `@DisplayName`.
- Do not change production code. If a ported test fails, re-check the port against the Swift original; if it still fails, leave it in place `@Disabled("S25: <reason>")` and report the exact case.

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
- Complete `BlenderamaDefenceTests.kt` (both tests in full).
- Add `CrossCuttingPortedTests.kt` with the other eight tests.

## Out of Scope
- Any production code change.
- Porting `CPUTests.swift` (S09) or `OnlineTests.swift` (S10).

## Acceptance Criteria
- [ ] All ten tests listed in Context exist in Kotlin and pass, or are disabled with a reported reason.
- [ ] No file under `Android/engine/src/main/` is changed.
- [ ] The CPU-driven tests use a seed and do not assert on wall-clock time.
- [ ] Build and existing tests green.

## Verification
```bash
source Android/scripts/env.sh
cd Android && ./gradlew :engine:test ktlintCheck --no-build-cache --rerun-tasks
cd .. && git diff --stat plan/android-rewrite...HEAD -- Android/engine/src/main | /usr/bin/grep -c . || true   # expect 0
```

## Estimate Rationale
1: ten tests against code that already exists, with the builder and helpers in place.

## Actuals
- **Actual points:**
- **What took longer / shorter than expected:**
- **Surprises / unknowns that surfaced:**
- **Review fixes applied:**

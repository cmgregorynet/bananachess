---
id: S08
title: Port the CPU evaluation
layer: domain
points: 3
depends_on: [S06, S03]
touches: [Android/engine/src/main/kotlin/com/charlesbarton/bananachess/engine/cpu/Evaluation.kt, Android/engine/src/main/kotlin/com/charlesbarton/bananachess/engine/cpu/EvaluationWeights.kt, Android/engine/src/test/kotlin/com/charlesbarton/bananachess/engine/cpu/EvaluationGoldenTest.kt, Android/engine/src/test/kotlin/com/charlesbarton/bananachess/engine/support/GoldenFixture.kt]
status: in_progress
branch: slice/android-rewrite/S08
started: 2026-11-23T13:16
completed:
actual_points:
review_fixes:
blocked_reason:
---

# S08 — Port the CPU evaluation

## Goal
The position scoring the computer opponent thinks with, proven equal to Swift on real positions.

## Context
- Port `Engine/Sources/BananaChessEngine/CPU/Evaluation.swift` (378 lines). TypeScript reference: `Web/src/engine/evaluation.ts`.
- Integer arithmetic only. Division truncates toward zero in both languages (`blenderDestroyed / 4` is −62). `winScore = 1_000_000`. Weights table is `EvaluationWeights` (`Evaluation.swift:3`).
- Sets are used only for membership, so iteration order never matters.
- **Golden data** (from S03): `Android/engine/src/test/resources/golden.json.gz` has 18 games, each with options, `seed`, `startingPlayer`, the ordered action description strings, and `samples[]` with `step`, `boardKey`, `positionKey` (decimal strings), `evaluationOne`, `evaluationTwo`. Rebuild each sampled state by calling `newGame` with the options and applying the actions by description through `RulesEngine`, then compare.

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
- `engine/cpu/EvaluationWeights.kt` and `engine/cpu/Evaluation.kt` with `score(state, for player, weights)`.
- `engine/src/test/.../support/GoldenFixture.kt`: loads `golden.json.gz` and replays a game to a given step.
- `EvaluationGoldenTest`: for every sample, `boardKey`, `positionKey`, `evaluationOne` and `evaluationTwo` equal the Swift values.

## Out of Scope
- The search (S09).
- Decoding `payloadJSON` or `link` from the golden file (S10).

## Acceptance Criteria
- [ ] Every golden sample matches on both keys and both evaluations.
- [ ] Tests added; build and existing tests green.

## Verification
```bash
source Android/scripts/env.sh
cd Android && ./gradlew :engine:test ktlintCheck
```

## Estimate Rationale
3: 378 lines of integer arithmetic with an exact per-position oracle.

## Actuals
- **Actual points:**
- **What took longer / shorter than expected:**
- **Surprises / unknowns that surfaced:**
- **Review fixes applied:**

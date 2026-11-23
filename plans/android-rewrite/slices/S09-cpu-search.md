---
id: S09
title: Port the CPU player and its four difficulty levels
layer: domain
points: 3
depends_on: [S08]
touches: [Android/engine/src/main/kotlin/com/charlesbarton/bananachess/engine/cpu/CpuPlayer.kt, Android/engine/src/main/kotlin/com/charlesbarton/bananachess/engine/cpu/SearchPlan.kt, Android/engine/src/main/kotlin/com/charlesbarton/bananachess/engine/cpu/Search.kt, Android/engine/src/test/kotlin/com/charlesbarton/bananachess/engine/cpu/CpuPlayerTest.kt]
status: todo
branch:
started:
completed:
actual_points:
review_fixes:
blocked_reason:
---

# S09 — Port the CPU player and its four difficulty levels

## Goal
Easy, Medium, Hard and Brutal computer opponents that pick the same kind of move as iOS within the same time limits.

## Context
- Port `Engine/Sources/BananaChessEngine/CPU/CPUPlayer.swift` (610 lines). TypeScript reference: `Web/src/engine/cpu.ts`. `CpuDifficulty` already exists (S04); `Evaluation` exists (S08).
- `SearchPlan.standard` (`CPUPlayer.swift:83`): easy depth 1, candidateFraction 0.5, 50 ms soft / 600 ms hard; medium depth 4, 220 / 450 ms, tempoMargin 48; hard depth 12, 1500 ms / 2 s; brutal depth 12, 2 s / 4 s, quiescenceDepth 8. Port `SearchPlan.previous` only if a ported test needs it.
- `selectAction` (`:182`): start from fully enforced `legalActions`; fraction ≥ 1 picks at random; depth ≤ 1 uses `pickShallow` (apply each with `Expected` dice and repetition off, rank by (value, action) descending, take the top `ceil(n × fraction)`, or all tied-best when the fraction is 0) then `breakTie`; otherwise `pickBestSearch`.
- Search (`:233`, `:334`): iterative deepening from 2; soft deadline checked before each iteration, hard deadline every 512 nodes, a timed-out iteration is discarded; root window ±2,000,000 with no root cut-off; inner nodes are minimax with alpha-beta (not negamax, because a skipped turn breaks alternation); a node maximises when the active player is the root player; every node uses unenforced `legalActions` and `Expected` battles; transposition table keyed by `positionKey` with bounds classified against the window the node was given (`:485`); move ordering by table move, then `staticRank` descending, then action descending (`:491`, ranks at `:578`); quiescence over forcing moves (`:510`); `preferringTempo` (`:286`).
- `breakTie` (`:310`): with a seed, `SplitMix64(seed xor positionKey xor turnNumber)` then a Lemire pick; without one, system randomness.
- A throwing `apply` inside the CPU is treated as "skip this move", as Swift's `try?` does.
- Time comes from an injected monotonic clock (`() -> Long` nanoseconds, default `System::nanoTime`) so tests can run without real waiting. The search is plain synchronous code; the caller chooses the thread.
- Tests to port: `Engine/Tests/BananaChessEngineTests/CPUTests.swift` (12).

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
- `SearchPlan`, `CpuPlayer(difficulty, weights, seed, plan, clock)` with `selectAction(state): GameAction?` and `selectMove`.
- The search, transposition table, move ordering, quiescence and tempo preference as described.
- Port the 12 Swift CPU tests: always legal, null when no action exists, a full CPU-vs-CPU match finishes, seeded Easy is reproducible, Medium beats Easy over a small series, each level respects its hard time cap.
- A test that the search never mutates the state it was given.

## Out of Scope
- Threading and the 320 ms minimum thinking time (S19).
- Tuning weights or plans.

## Acceptance Criteria
- [ ] The ported CPU tests pass.
- [ ] Each level returns within its hard cap plus 250 ms on this Mac, measured in a test.
- [ ] With a fixed seed and a fake clock that never expires, the same position gives the same action on repeated runs for every level.
- [ ] Build and existing tests green.

## Verification
```bash
source Android/scripts/env.sh
cd Android && ./gradlew :engine:test ktlintCheck
```

## Estimate Rationale
3: an intricate search, but no cross-device exactness is required (iOS itself is time-dependent), and 12 tests carry over.

## Actuals
- **Actual points:**
- **What took longer / shorter than expected:**
- **Surprises / unknowns that surfaced:**
- **Review fixes applied:**

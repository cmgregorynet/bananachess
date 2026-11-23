---
id: S05
title: Port the core rules: legal moves, blending, delivery, blender combat, buy-back, repetition, turn end
layer: domain
points: 3
depends_on: [S04]
touches: [Android/engine/src/main/kotlin/com/charlesbarton/bananachess/engine/rules/, Android/engine/src/test/kotlin/com/charlesbarton/bananachess/engine/rules/]
status: todo
branch:
started:
completed:
actual_points:
review_fixes:
blocked_reason:
---

# S05 — Port the core rules: legal moves, blending, delivery, blender combat, buy-back, repetition, turn end

## Goal
The standard game, complete and exact: every legal action and every transition, proven by replaying the 27 fixture games that use standard rules without blender battles.

## Context
- Port `Engine/Sources/BananaChessEngine/RulesEngine.swift` (788 lines). The TypeScript port is `Web/src/engine/rules.ts`. S04 already provides the model in `Android/engine/src/main/kotlin/com/charlesbarton/bananachess/engine/model/`.
- Public API to reproduce: `legalMoves(in)`, `legalMoves(for player/piece, enforcingRepetition)`, `validate`, `legalActions(in, enforcingRepetition = true)`, `apply(action, to, battles = Dice, enforcingRepetition = true): Transition(state, events)`, `spawnSquares`, `isFruitDepleted`, `outcome(for scores)`, and `RulesError` with user-readable messages. Constants: `rulesVersion = 18`, `blenderRestoreCost = 3`, `repetitionLimit = 3`.
- Movement (`RulesEngine.swift:144-197`): fruit steps orthogonally and may wrap according to the board wrap; blenders step diagonally and **never** wrap. Fruit never lands on an occupied square. A blender may land on anything except a blender standing on one of that blender's owner's spawn squares.
- `apply` order (`:305-356`): validate, check repetition, then either restore (position set, owner −3, `blenderRestored`, `scoreChanged`) or move (`moved` first, then collision / blend / delivery), append to history, announce golden pineapples, record the wildcard scoring turn, `finishTurn`, and last of all record the new `boardKey`.
- Repetition (`:109`): apply with the rule off; the action is refused if the resulting `boardKey` has already been seen twice.
- `finishTurn` (`:694`): turn number increments; fruit depletion ends the match by score (an off-board banana still counts as held); otherwise the opponent moves if they have a legal action, else `turnPassed` then `turnChanged(current)`, else the match ends. The skip checks use the same `enforcingRepetition` flag as the call.
- In this slice, blender onto blender **always** destroys the defender (the battles-off path, `:401`), and wildcard branches are left as clearly marked no-ops that S06 fills in. Structure the code so S06 adds to it without rewriting it.
- Events must be emitted in exactly the Swift order. Event strings for the fixture are defined in `Engine/Sources/ConformanceFixtures/main.swift:101-134`; write a test-side `describe(event)` that reproduces them.
- Fixture step fields: `action`, `events[]`, `after` (`id@row,col` or `id@-`, space separated, id order), `activePlayer`, `scores [one, two]`, `turnNumber`, and `legalBefore[]` on the first 8 steps.

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
- `engine/rules/RulesEngine.kt` (split into a few focused files if it reads better) with the API above.
- Test-side helpers: `describe(action)`, `describe(event)`, `places(state)` matching the fixture formats.
- Conformance replay test over the fixture games with `ruleSet == standard` and `blenderBattles == false` (27 games): for every step compare `legalBefore` when present (exact list, in order), find and apply the action by its description, then compare `events`, `after`, `activePlayer`, `scores`, `turnNumber`; at the end compare the outcome string.
- Focused unit tests for: validation errors, the repetition refusal, a turn pass, buy-back onto either empty spawn with no affordability check, a blender refused entry to an occupied spawn.

## Out of Scope
- Wildcard rules and dice battles (S06).
- `GameState.applying(event)` (S06).
- The broader port of Swift unit tests (S07).

## Acceptance Criteria
- [ ] All 27 standard, battles-off fixture games replay with zero differences, including event strings.
- [ ] `apply` never mutates its input state (a test applies an action and asserts the original state is unchanged, deep-equal to a copy taken before).
- [ ] Tests added for the focused cases; build and existing tests green.

## Verification
```bash
source Android/scripts/env.sh
cd Android && ./gradlew :engine:test ktlintCheck
```

## Estimate Rationale
3: the largest single Swift file, but split from S06 on purpose, with a step-by-step oracle that names the first step that differs.

## Actuals
- **Actual points:**
- **What took longer / shorter than expected:**
- **Surprises / unknowns that surfaced:**
- **Review fixes applied:**

---
id: S06
title: Port wildcard rules, blender battles and event replay
layer: domain
points: 3
depends_on: [S05]
touches: [Android/engine/src/main/kotlin/com/charlesbarton/bananachess/engine/rules/, Android/engine/src/test/kotlin/com/charlesbarton/bananachess/engine/rules/]
status: in_progress
branch: slice/android-rewrite/S06
started: 2026-11-23T13:10
completed:
actual_points:
review_fixes:
blocked_reason:
---

# S06 — Port wildcard rules, blender battles and event replay

## Goal
Finish the rules engine: the four wildcard rules, dice battles and `GameState.applying(event)`. Proven by replaying all 108 fixture games, events included.

## Context
- Builds on S05's `Android/engine/src/main/kotlin/com/charlesbarton/bananachess/engine/rules/`. Swift sources: `Engine/Sources/BananaChessEngine/RulesEngine.swift` (`resolveCollision` `:401`, dice `:447-474`, `blend` wildcard part `:557`, `deliver` `:648`, `announceGoldenPineapples` `:363`, `recordScoringTurnForWildcards` `:385`, `wildcardWin` `:631`, `returnWaitingBananas` `:742`, `returnScheduledBlenders` `:619`), `Engine/Sources/BananaChessEngine/Wildcards.swift`, `Engine/Sources/BananaChessEngine/BlenderBattle.swift`, `Engine/Sources/BananaChessEngine/GameState+Events.swift`.
- **Battle dice** (must match exactly so two devices agree): `base = positionKey(state before the move) + (row*6 + col of the defender's square)`; `attackerRoll = (SplitMix64(base + 0x9E3779B9).next() % 6) + 1`; `defenderRoll = (SplitMix64(base + 0x85EBCA6B).next() % 6) + 1`. The modulo is **unsigned** 64-bit. Strength is `battleStrength` on the pre-move state; the attacker adds `battleAttackerBonus = 1`; a tie goes to the defender; a losing attacker leaves the board and the defender keeps the square. A blender attacking its own blender never battles.
- `BattleResolution.Expected` (both rolls 4) exists for the CPU; implement it here.
- **Wildcards**: Golden Pineapple (a player's last pineapple; blend value doubled, delivery tripled), Banana Split (`bananaDeliveriesToWin = 3`; a delivered banana goes off-board and returns to its home at the turn boundary if the home is empty), Tropical Smoothie (first time a player has blended one of each kind: +3, `smoothiePoured`), Blenderama (six of your own fruit through your own blenders, unless countered). Win checks run for player one then two, before and after the turn boundary.
- `GameState.applying(event)` replays presentation only: positions, removals, respawn turn, scores, active player, status. It does not touch `turnNumber`, `history`, `boardHistory` or wildcard progress.
- Events that never occur in the fixture (`blenderRespawned`, `smoothie`, `turnPassed`) still need correct `applying` behaviour.

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
- Fill in the wildcard branches and the battle path left by S05.
- `engine/rules/GameStateEvents.kt`: `GameState.applying(event)`.
- Extend the conformance replay to all 108 games.
- A replay-equivalence test: for every step of every fixture game, folding `applying` over the transition's events from the previous state gives the same pieces, scores, active player and status as the transition's state.
- Unit tests for a battle won, lost and tied; a Banana Split win; a Blenderama win and a countered one; a golden pineapple announcement.

## Out of Scope
- CPU (S08, S09).
- Payload encoding (S10).

## Acceptance Criteria
- [ ] All 108 fixture games replay with zero differences in legal actions, events, board, active player, scores, turn number and outcome.
- [ ] The replay-equivalence test passes for all 13,829 steps.
- [ ] Tests added for battles and each wildcard; build and existing tests green.

## Verification
```bash
source Android/scripts/env.sh
cd Android && ./gradlew :engine:test ktlintCheck
```

## Estimate Rationale
3: wildcard bookkeeping has many small ordering rules; the 81 remaining fixture games catch each one.

## Actuals
- **Actual points:**
- **What took longer / shorter than expected:**
- **Surprises / unknowns that surfaced:**
- **Review fixes applied:**

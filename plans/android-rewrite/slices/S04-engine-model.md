---
id: S04
title: Port the engine model, opening setup, random generator and position keys
layer: domain
points: 3
depends_on: [S01]
touches: [Android/engine/src/main/kotlin/com/charlesbarton/bananachess/engine/model/, Android/engine/src/main/kotlin/com/charlesbarton/bananachess/engine/setup/, Android/engine/src/main/kotlin/com/charlesbarton/bananachess/engine/random/, Android/engine/src/main/kotlin/com/charlesbarton/bananachess/engine/cpu/CpuDifficulty.kt, Android/engine/src/main/kotlin/com/charlesbarton/bananachess/engine/EngineInfo.kt, Android/engine/src/test/kotlin/com/charlesbarton/bananachess/engine/EngineInfoTest.kt, Android/engine/src/test/kotlin/com/charlesbarton/bananachess/engine/model/, Android/engine/src/test/kotlin/com/charlesbarton/bananachess/engine/setup/, Android/engine/src/test/kotlin/com/charlesbarton/bananachess/engine/random/, Android/engine/src/test/kotlin/com/charlesbarton/bananachess/engine/support/]
status: in_progress
branch: slice/android-rewrite/S04
started: 2026-11-23T12:54
completed:
actual_points:
review_fixes:
blocked_reason:
---

# S04 — Port the engine model, opening setup, random generator and position keys

## Goal
Every value type the game is made of, the three opening arrangements, and the exact random and hashing primitives. Proven by reproducing all 108 fixture openings.

## Context
- Swift sources to port, file for file: `Engine/Sources/BananaChessEngine/Board.swift`, `PlayerID.swift`, `Piece.swift`, `Move.swift`, `GameAction.swift`, `GameEvent.swift`, `BlenderBattle.swift` (types only), `GameState.swift`, `Wildcards.swift`, `BoardSetup.swift`, `BoardWrap.swift`, `GameSetup.swift`, `CPU/SeededGenerator.swift`. The TypeScript equivalents are `Web/src/engine/types.ts`, `board.ts`, `state.ts`, `events.ts`, `setup.ts`, `random.ts`.
- Exactness rules (each is load-bearing):
  - **SplitMix64**: `state += 0x9E3779B97F4A7C15; z = state; z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9; z = (z xor (z ushr 27)) * 0x94D049BB133111EB; return z xor (z ushr 31)`, all wrapping 64-bit.
  - **`nextBelow(n)`** is Swift's `next(upperBound:)`, Lemire's method: `m = r * n` as a 128-bit product; if `low(m) < n`, compute `t = (0 - n) % n` (unsigned) and redraw while `low(m) < t`; return `high(m)`. Plain modulo deals a different board.
  - **`shuffled`** is forward Fisher-Yates: `amount = count; i = 0; while (amount > 1) { swap(i, i + nextBelow(amount)); amount--; i++ }`.
  - **Random deal** (`GameSetup.swift:118`): shuffle all 36 squares (row-major) with the seed; for player one then two, each fruit in `fruitOrder` takes the first pool square not on that player's delivery row, then each of the two blenders takes the next remaining square.
  - **`boardKey`** and **`positionKey`** (`GameState.swift:232`, `:259`): FNV-1a 64 (offset `0xCBF29CE484222325`, prime `0x100000001B3`), integers mixed as 8 little-endian bytes of the signed 64-bit value. See the Swift source for the exact field order; `positionKey` also mixes each piece id's UTF-8 bytes and both scores.
  - **Ordering**: `Position` by (row, column); `PieceID` by plain string comparison; `Move` by (pieceID, destination) ignoring origin, while equality includes origin; every `move` action sorts before every `restoreBlender`, restores by (pieceID, at).
  - **Description strings**: `Position` is `r{row}c{column}`; `Move` is `{id} {origin}→{destination}` (U+2192); restore is `restore {id} at r{r}c{c}`. The fixtures match on these.
- `GameState.pieces` is always sorted by id, and the constructor seeds `boardHistory` with the opening `boardKey`.
- Represent 64-bit keys as `ULong`. Scores and rows stay `Int`.
- Also define `CpuDifficulty` (easy, medium, hard, brutal) with `displayName` and `blurb` copied from `Engine/Sources/BananaChessEngine/CPU/CPUPlayer.swift:3-29`. The search plan belongs to S09.
- Fixture: the system property `conformance.fixture` holds the path to `Web/fixtures/conformance.json.gz`. Each game has `ruleSet`, `boardSetup`, `boardWrap`, `blenderBattles`, `startingPlayer`, `seed` and `opening.pieces[]` with `id, owner, kind, row?, column?, homeRow?, homeColumn?`.

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
- Port the listed types under `engine/model/` as immutable data classes, enums and sealed interfaces (`GameAction`, `GameEvent`, `GameStatus`, `Outcome`).
- `engine/random/`: `SplitMix64`, `nextBelow`, `shuffled`.
- `engine/setup/GameSetup.newGame(startingPlayer, ruleSet, boardSetup, boardWrap, blenderBattles, seed)` with the Standard, Blendy and Random layouts and the tuning constants.
- `GameState` with its lookups, derived values (`fruitStandingValue`, `battleStrength`, `isGolden`, `edges(for)`), `boardKey`, `positionKey`, and copy-based updaters used by the rules slices.
- `engine/cpu/CpuDifficulty.kt`.
- A shared test helper under `engine/src/test/.../support/` that loads and parses the conformance fixture once.
- Tests: SplitMix64 against known outputs taken from the TypeScript port; `nextBelow` and `shuffled` edge cases; ordering and description strings; **all 108 fixture openings reproduced exactly** (ids, owners, kinds, squares, homes, active player, turn number 1).

## Out of Scope
- Legal moves and `apply` (S05, S06).
- `GameState.applying(event)` (S06).
- Serialization (S10).
- CPU search and evaluation (S08, S09).

## Acceptance Criteria
- [ ] A test builds each of the 108 fixture games with `newGame` and its pieces equal the fixture's `opening.pieces` exactly, including all 36 random-board games.
- [ ] Description strings for every action in the first step of each fixture game parse and round-trip.
- [ ] `:engine` has no `android` or `androidx` import.
- [ ] Tests added for the random primitives, ordering and keys; build and existing tests green.

## Verification
```bash
source Android/scripts/env.sh
cd Android && ./gradlew :engine:test ktlintCheck
cd .. && /usr/bin/grep -rn --include='*.kt' -E "^import (android|androidx)\." Android/engine/src/main | /usr/bin/grep -c . || true   # expect 0
```

## Estimate Rationale
3: about 1,000 lines of straightforward types plus four exact algorithms, with the TypeScript port as a worked reference and 108 openings as the oracle.

## Actuals
- **Actual points:**
- **What took longer / shorter than expected:**
- **Surprises / unknowns that surfaced:**
- **Review fixes applied:**

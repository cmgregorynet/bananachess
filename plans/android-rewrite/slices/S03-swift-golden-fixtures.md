---
id: S03
title: Emit golden link, key and evaluation fixtures from the Swift engine
layer: backend
points: 2
depends_on: []
touches: [Engine/Package.swift, Engine/Sources/GoldenFixtures/, Android/engine/src/test/resources/golden.json.gz]
status: done
branch: slice/android-rewrite/S03
started: 2026-11-23T12:46
completed: 2026-11-23T12:50
actual_points: 2
review_fixes: added battle-step sampling to meet coverage criterion
blocked_reason:
---

# S03 — Emit golden link, key and evaluation fixtures from the Swift engine

## Goal
Give the Kotlin port real Swift output to check against for the three things the conformance fixture does not pin: the exact link-play JSON and URL, the two position hashes, and the CPU evaluation.

## Context
- This slice is Swift only. It needs no Android toolchain and does not source `Android/scripts/env.sh`.
- Build with `DEVELOPER_DIR=/Applications/Xcode-26.5.0.app/Contents/Developer` (Xcode is not on the default `xcode-select` path on this machine).
- Follow the existing executable `Engine/Sources/ConformanceFixtures/main.swift` (drivers at `:138-166`, game loop at `:178-226`) and its target declaration in `Engine/Package.swift:17-21`. **Do not change that executable or its output**; the web port depends on it.
- `Evaluation` is `internal` (`Engine/Sources/BananaChessEngine/CPU/Evaluation.swift:78`, `score` at `:83`), so the new target uses `@testable import BananaChessEngine`; `swift run` builds in debug, where that works.
- Types to use: `MatchPayload` and `MatchCodec` (`Engine/Sources/BananaChessEngine/Online/MatchPayload.swift`), `MatchLink` (`Engine/Sources/BananaChessEngine/Online/MatchLink.swift`), `GameState.boardKey` / `positionKey` (`Engine/Sources/BananaChessEngine/GameState.swift:232`, `:259`), `EvaluationWeights` defaults.

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
- New executable target `GoldenFixtures` in `Engine/Package.swift` and `Engine/Sources/GoldenFixtures/main.swift`.
- Play 18 deterministic games: every combination of rule set (2) × board setup (3) × board wrap (3), with blender battles on for odd-numbered games, seeds 1001…1018, using the same `wander` selection rule as `ConformanceFixtures`, capped at 80 steps.
- For each game write: the options and seed, the starting player, the ordered list of action description strings, and `samples` taken at steps 0, 1, 2, 5, 10, 20, 40 and the final step (those that exist).
- Each sample holds: `step`; `payloadJSON` (the exact UTF-8 string from `MatchCodec.encode` of a `MatchPayload` with `matchID` "golden-<seed>", `moveNumber` = history count, and `lastMove` set to the real last action, player and events when step > 0); `link` (the `bananachess://` URL from `MatchLink`); `boardKey` and `positionKey` as decimal strings; `evaluationOne` and `evaluationTwo` (`Evaluation.score` for each player with default weights).
- Top level also records `rulesVersion` and `schemaVersion`. Output uses sorted keys. Write to the path given as the first argument, then gzip to `Android/engine/src/test/resources/golden.json.gz` and commit that file.
- Document the regeneration command in a comment at the top of `main.swift`.

## Out of Scope
- Any Kotlin code.
- Changing `ConformanceFixtures` or `Web/fixtures/`.

## Acceptance Criteria
- [ ] `swift run GoldenFixtures <path>` succeeds and prints how many games and samples it wrote.
- [ ] The committed `golden.json.gz` contains 18 games, at least 100 samples, and at least one sample each with: a wildcard rule set, a random board, an ended game, a destroyed blender (a piece with no position), and a `lastMove` containing a `blenderBattle` event.
- [ ] Running the generator twice produces byte-identical JSON.
- [ ] `swift test` in `Engine/` still passes, and `Web/fixtures/conformance.json.gz` is unchanged.

## Verification
```bash
export DEVELOPER_DIR=/Applications/Xcode-26.5.0.app/Contents/Developer
cd Engine && swift run GoldenFixtures /tmp/golden-a.json && swift run GoldenFixtures /tmp/golden-b.json && cmp /tmp/golden-a.json /tmp/golden-b.json
swift test 2>&1 | tail -3
cd .. && gunzip -c Android/engine/src/test/resources/golden.json.gz | python3 -c "import json,sys; d=json.load(sys.stdin); print(len(d['games']), sum(len(g['samples']) for g in d['games']), d['rulesVersion'], d['schemaVersion'])"
git diff --stat plan/android-rewrite...HEAD -- Web/ | /usr/bin/grep -c . || true   # expect 0
```

## Estimate Rationale
2: one small Swift executable modelled on an existing one; the unknown is `@testable` access to the evaluation.

## Actuals
- **Actual points:** 2. The estimate was 2.
- **What took longer / shorter than expected:** `@testable import` of the evaluation worked first time under `swift run`, so the expected unknown cost nothing. The only extra work was missing battle coverage.
- **Surprises / unknowns that surfaced:**
  - The link blob is raw DEFLATE (never a zlib header); all 146 links inflate to exactly their payload JSON.
  - `boardHistory` values above `Long.MAX_VALUE` occur (max 18405796021788992704).
  - Not pinned by the fixture: `blenderRespawned`, `smoothie`, `turnPassed`, a draw outcome, win reasons other than score, and `\/` escaping inside a payload (no match id contains a slash).
  - `respawnTurn` never appears; off-board pieces simply have no `position` key.
- **Review fixes applied:** The fixed sample steps produced no sample with a blender battle in `lastMove`, so the generator also samples the first battle step in each game (146 samples, up from 144).

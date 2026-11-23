---
id: S07
title: Port the Swift engine tests the fixture does not cover
layer: test
points: 3
depends_on: [S06]
touches: [Android/engine/src/test/kotlin/com/charlesbarton/bananachess/engine/rulesported/, Android/engine/src/test/kotlin/com/charlesbarton/bananachess/engine/support/BoardBuilder.kt]
status: todo
branch:
started:
completed:
actual_points:
review_fixes:
blocked_reason:
---

# S07 — Port the Swift engine tests the fixture does not cover

## Goal
The fixture proves ordinary play. This slice ports the hand-written Swift tests for positions that random play rarely reaches, so a future rules change in Kotlin fails loudly.

## Context
- Swift tests: `Engine/Tests/BananaChessEngineTests/` — `SetupAndMovementTests.swift` (31), `ScoringTests.swift` (35), `WildcardTests.swift` (49), `EndStateAndDeterminismTests.swift` (16), `BlenderamaDefenceTests.swift` (2). `TestSupport.swift` provides `BoardBuilder` (custom positions with auto-numbered ids `{owner}.fruit.N` / `{owner}.blender.N`, `destroyedBlender`, `state(active:ruleSet:scores:)`), `position(r, c)`, `GameState.move(id, to:)`, `idleTransition`, `advanceTurns`, and event filters.
- `OnlineTests.swift` and `CPUTests.swift` belong to S10 and S09; do not port them here.
- Port a test when it checks a rule or an edge case. Skip a test that only restates something the 108-game replay already proves (for example, the standard opening layout). Record what was skipped and why in the slice report.
- Events that never occur in the fixture and so need direct tests: `turnPassed`, `blenderRespawned`, and `RulesError` cases.

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
- `BoardBuilder` and helpers in `engine/src/test/.../support/BoardBuilder.kt`.
- Ported tests under `engine/src/test/.../rulesported/`, one Kotlin file per Swift file, keeping the Swift test names recognisable.
- At minimum: every wildcard test, every end-state and determinism test, both Blenderama defence tests, and the scoring and movement tests that build custom positions.

## Out of Scope
- Changing production engine code. If a ported test fails, report the exact case; a rules fix is a follow-up slice, not a silent edit here.

## Acceptance Criteria
- [ ] At least 100 ported tests pass.
- [ ] Every skipped Swift test is listed with a one-line reason in the slice report.
- [ ] No file under `Android/engine/src/main/` is changed.
- [ ] Build and existing tests green.

## Verification
```bash
source Android/scripts/env.sh
cd Android && ./gradlew :engine:test ktlintCheck
cd .. && git diff --stat plan/android-rewrite...HEAD -- Android/engine/src/main | /usr/bin/grep -c . || true   # expect 0
```

## Estimate Rationale
3: mostly mechanical, but about 130 tests and a test builder to translate.

## Actuals
- **Actual points:**
- **What took longer / shorter than expected:**
- **Surprises / unknowns that surfaced:**
- **Review fixes applied:**

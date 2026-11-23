---
id: S19
title: Add matches against the computer
layer: ui
points: 2
depends_on: [S18, S09]
touches: [Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/MatchViewModel.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/MatchUiState.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/MatchScreen.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/BananaChessApp.kt, Android/app/src/test/kotlin/com/charlesbarton/bananachess/match/CpuPlayTest.kt]
status: todo
branch:
started:
completed:
actual_points:
review_fixes:
blocked_reason:
---

# S19 — Add matches against the computer

## Goal
"Play Computer" works at all four levels without ever freezing the screen.

## Context
- iOS source: `App/BananaChess/Match/MatchViewModel.swift:529-560`. The engine's `CpuPlayer` (S09) is synchronous and can take up to 4 seconds on Brutal.
- When the mode is `Computer`, the match is not over, the CPU is to move and nothing is resolving: set `isOpponentThinking`, snapshot the authoritative state, and run `cpu.selectAction(snapshot)` on an **injected** background dispatcher (default `Dispatchers.Default`, named in the constructor). The thinking time is never less than 320 ms, so a turn reads as a turn. Back on the main dispatcher: clear thinking, apply through `RulesEngine.apply`, animate the events.
- Input is locked while the opponent is thinking. The turn indicator reads "<Difficulty> CPU is thinking…".
- If the app goes to the background while the CPU is thinking, the search is **not** cancelled; its reply is queued and animates when the player returns (this is the behaviour in commit "A move waits for the player to come back").
- The CPU job is stored, cancelled on restart and when the view model is cleared.
- If the CPU returns no action, the engine has already passed or ended the turn; do nothing.
- Home's CPU sheet (S13) already produces `MatchMode.Computer(difficulty, humanPlays)`; this slice makes that route start a real match. When the human plays second, the CPU moves first on entry.
- Names: "You" and "<Difficulty> CPU"; the board is rotated when the human is player two. "Play again" is available after a CPU match.

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
- CPU scheduling in `MatchViewModel` with the injected dispatcher and a fake-able `CpuPlayer` factory.
- Thinking indicator text and input lock; route wiring for `Computer` matches.
- Tests on virtual time with a stub CPU: the reply is applied no sooner than 320 ms; input is locked while thinking; a reply that arrives while inactive is held and plays on return; restart cancels a pending reply; the CPU moves first when the human plays second.
- One real-engine test: Home → Play Computer (Easy) → make a move → the indicator contains "You to move" again.

## Out of Scope
- Changing the CPU itself.
- Resume (S20).

## Acceptance Criteria
- [ ] A stub CPU that blocks for 2 seconds of real time does not block the test's main dispatcher (the UI state shows thinking while it runs).
- [ ] No `GlobalScope` and no hard-coded `Dispatchers.Default` outside the constructor default.
- [ ] Tests added; build, lint and existing tests green.

## Verification
```bash
source Android/scripts/env.sh
cd Android && ./gradlew :app:testDebugUnitTest :app:lintDebug ktlintCheck :app:assembleDebug
cd .. && /usr/bin/grep -rn --include='*.kt' "GlobalScope" Android/app/src/main | /usr/bin/grep -c . || true   # expect 0
```

## Estimate Rationale
2: a small amount of code in a place where threading mistakes are easy; the tests are the work.

## Actuals
- **Actual points:**
- **What took longer / shorter than expected:**
- **Surprises / unknowns that surfaced:**
- **Review fixes applied:**

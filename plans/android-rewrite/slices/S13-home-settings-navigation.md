---
id: S13
title: Build settings storage, the Home screen and app navigation
layer: ui
points: 3
depends_on: [S12]
touches: [Android/app/src/main/kotlin/com/charlesbarton/bananachess/settings/, Android/app/src/main/kotlin/com/charlesbarton/bananachess/home/, Android/app/src/main/kotlin/com/charlesbarton/bananachess/BananaChessApp.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/MainActivity.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/MatchMode.kt, Android/app/src/test/kotlin/com/charlesbarton/bananachess/settings/, Android/app/src/test/kotlin/com/charlesbarton/bananachess/home/, Android/app/src/test/kotlin/com/charlesbarton/bananachess/ScaffoldTest.kt]
status: done
branch: slice/android-rewrite/S13
started: 2026-11-23T13:11
completed: 2026-11-23T13:20
actual_points: 3
review_fixes: experimental coroutine-test calls replaced; temp screenshot test removed
blocked_reason:
---

# S13 — Build settings storage, the Home screen and app navigation

## Goal
The start screen with every game option, remembered between launches, and the navigation shell the other screens plug into.

## Context
- iOS sources: `App/BananaChess/Home/HomeView.swift`, `App/BananaChess/Home/HomeViewModel.swift`, `App/BananaChess/Match/MatchMode.swift`.
- **Menu** (Android v1): "Local Two Player" / "Share one phone and take turns." (primary); "Play Computer" / "<Difficulty> · <blurb>" opening a bottom sheet with a four-way difficulty picker (Easy, Medium, Hard, Brutal), a "You move first" switch and "Start match"; "Invite by Link" / "Send a friend a link and play." (its action is wired in S21; here it navigates to a placeholder). **There is no "Play Online" entry on Android** (Game Center does not exist here).
- **Options panel**: "Board" (Standard / Blendy / Random) and "Edges" (Walls / Sides / All) as rows of capsule buttons with a blurb that follows the selection; checkboxes "Wildcard rules" ("Golden Pineapple, Banana Split, Tropical Smoothie, Blenderama.") and "Blender battles" ("Blenders fight for a square instead of just taking it. Half your standing fruit, plus one for attacking, plus a die each."). Display names and blurbs come from `Engine/Sources/BananaChessEngine/BoardSetup.swift:18-30` and `Engine/Sources/BananaChessEngine/BoardWrap.swift:23-35`; add them as extension properties in `:app` if the engine types do not carry them.
- **Title**: three `PieceArtwork` at 46 dp (banana bottom seat, blender top seat, strawberry bottom seat), "Banana Chess", "Race the fruit. Mind the blenders." Then "How to play".
- The screen scrolls (content overflows on small phones and large font sizes).
- **Settings** (DataStore Preferences; defaults in brackets): `lastCPUDifficulty` [medium], `lastCPUHumanPlaysFirst` [true], `wildcardRulesEnabled` [false], `boardSetup` [standard], `boardWrap` [flat], `blenderBattles` [false], `hasSeenTeachingOverlay` [false]. A read failure falls back to defaults silently, with a comment saying why (preferences are best-effort).
- `MatchOptions(ruleSet, boardSetup, boardWrap, blenderBattles)` is built from the settings. `newGame` always starts with player one; in a CPU match the human is player two when "You move first" is off.
- `MatchMode` (sealed): `LocalVersus`, `Computer(difficulty, humanPlays)`, `ByLink(matchID, localPlayer)`, with the display names from `MatchMode.swift` ("Player One"/"Player Two"; "You"/"<Difficulty> CPU"; "You"/"Your opponent") and `bottomPlayer`.
- **Navigation**: Navigation Compose in `BananaChessApp.kt` with routes Home, Match and Rules. Match and Rules are placeholder screens here; S14 and S17 replace them. Pass match start parameters through a small in-memory holder owned by the activity-scoped graph, not through route strings.
- Accessibility: each menu button reads "<title>. <detail>"; checkboxes expose role, state and the detail as a description; picker buttons expose selected state.
- **Established by S12:** `Modifier.clip(shape)` and `graphicsLayer { clip = true }` each add a semantics node in this Compose version. Anything that must stay out of the accessibility tree clips in the draw phase instead (see the tray in `art/PieceArtwork.kt`). `PieceArtwork(kind, seat, modifier, isGolden, showsTray)` takes named arguments, needs a bounded size, and its golden halo draws outside its bounds. `isReducedMotion(context)` lives in `art/PieceArtwork.kt`.

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
- `settings/SettingsStore.kt` (interface plus DataStore implementation and an in-memory fake for tests).
- `match/MatchMode.kt`.
- `home/HomeViewModel.kt` (state as `StateFlow`, setters persist, `startLocal()`, `startComputer()`, `options`), `home/HomeScreen.kt` and its components, the CPU options sheet.
- `BananaChessApp.kt` navigation host; `MainActivity` hosts it inside `PaperTheme`.
- Tests: view model defaults; each setter persists and survives a new view model on the same store; `options` reflects the settings; Robolectric Compose tests that the Board picker is exclusive and its blurb follows the selection, the checkboxes toggle and report state, and "Local Two Player" navigates to the Match route.

## Out of Scope
- The Match screen (S17), the Rules screen (S14), link handling (S21, S22), the resume entry (S20).

## Acceptance Criteria
- [ ] All six game settings persist across view model re-creation in a test.
- [ ] No "Play Online" or Game Center text exists in `:app`.
- [ ] Every interactive element on Home has a content description or text that TalkBack can read, asserted in a Compose test for the menu buttons, pickers and checkboxes.
- [ ] Tests added; build, lint and existing tests green.

## Verification
```bash
source Android/scripts/env.sh
cd Android && ./gradlew :app:testDebugUnitTest :app:lintDebug ktlintCheck :app:assembleDebug
cd .. && /usr/bin/grep -rni --include='*.kt' -E "game center|play online" Android/app/src/main | /usr/bin/grep -c . || true   # expect 0
```

## Estimate Rationale
3: a screen, a store, a view model and the navigation shell. The four prior UI slices in the log landed on estimate (6 estimated, 6 actual).

## Actuals
- **Actual points:** 3. The estimate was 3.
- **What took longer / shorter than expected:** The bottom sheet, type-safe navigation routes and real DataStore all worked under Robolectric first time. Deciding how DataStore stays one instance per file, and how the view model handles an edit made before the async load, took longer.
- **Surprises / unknowns that surfaced:**
  - A fourth route, `Route.Invite`, exists as the "Invite by Link" placeholder. S21/S22 remove it.
  - `MatchOptions` and `MatchStart` are defined in `:app` (`match/MatchMode.kt`).
  - Settings load asynchronously, so Home shows defaults for a moment before stored values; a choice made before the load lands is kept.
  - The match start is handed over through an activity-scoped `AppGraph.matchStart` holder, which is null after process death. S17 must handle that.
  - `ScaffoldTest` asserts on the placeholder tags, so S14 and S17 update it when they replace the placeholders.
  - Outside `touches`: `Android/app/src/test/kotlin/com/charlesbarton/bananachess/match/MatchModeTest.kt`.
- **Review fixes applied:** Replaced experimental coroutine-test calls; wrapped the preview's view model in `remember`; removed a temporary screenshot test.

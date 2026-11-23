---
id: S14
title: Build the Rules screen and the first-match teaching overlay
layer: ui
points: 2
depends_on: [S13]
touches: [Android/app/src/main/kotlin/com/charlesbarton/bananachess/rules/, Android/app/src/test/kotlin/com/charlesbarton/bananachess/rules/, Android/app/src/main/kotlin/com/charlesbarton/bananachess/BananaChessApp.kt]
status: todo
branch:
started:
completed:
actual_points:
review_fixes:
blocked_reason:
---

# S14 — Build the Rules screen and the first-match teaching overlay

## Goal
"How to play", and the four-page introduction a new player sees once.

## Context
- iOS source: `App/BananaChess/Rules/RulesView.swift` (rules copy at `:13-96`, overlay at `:147-195`).
- Rules sections, in order: "The turn", "How pieces move", "Scoring" (rows "blend X · deliver Y" per fruit, read by TalkBack as "<kind>, blended for X points, delivered for Y"), "Blending cuts both ways", "Blender combat", "Fruit is the clock", "Wildcard rules" (four entries). Copy is verbatim. The text must contain: "Blend your own and you lose it", "below zero", "destroys that one outright", "no extra move", "three points".
- Teaching overlay: four pages with icon, title and body, page dots, "Next", and "Play" on the last page: "Move one piece" (banana), "Reach the far edge" (strawberry), "Blending cuts both ways" (blender), "Blenders knock out" (pineapple).
- **Deliberate correction:** the iOS page-4 body is stale ("both explode. They are gone for two turns, then return to their spawns", `RulesView.swift:155`). Write page 4 to agree with the Rules screen instead: a blender that runs into another destroys it outright, and a lost blender never comes back on its own — it costs a turn and three points to buy back.
- The overlay does not teach the self-blend tactic (`RulesView.swift:145-146`).
- `SettingsStore.hasSeenTeachingOverlay` (S13) records that it has been shown. This slice builds the overlay composable and its state holder; S17 places it on the Match screen.
- Replace the Rules placeholder route in `BananaChessApp.kt`.

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
- `rules/RulesScreen.kt` with a top bar titled "How to play" and a "Done" action.
- `rules/TeachingOverlay.kt` with paging state, "Next" / "Play", and a callback that marks it seen.
- Wire the Rules route.
- Tests: the required phrases are present; the overlay advances through four pages and calls the completion callback on "Play"; page 4 does not contain "two turns".

## Out of Scope
- Showing the overlay on the Match screen (S17).
- Fixing the stale iOS copy in `App/`.

## Acceptance Criteria
- [ ] Home's "How to play" opens the Rules screen and "Done" returns.
- [ ] A Compose test finds each of the five required phrases.
- [ ] Tests added; build, lint and existing tests green.

## Verification
```bash
source Android/scripts/env.sh
cd Android && ./gradlew :app:testDebugUnitTest :app:lintDebug ktlintCheck :app:assembleDebug
```

## Estimate Rationale
2: static copy and a four-page pager.

## Actuals
- **Actual points:**
- **What took longer / shorter than expected:**
- **Surprises / unknowns that surfaced:**
- **Review fixes applied:**

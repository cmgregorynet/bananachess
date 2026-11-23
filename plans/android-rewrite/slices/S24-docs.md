---
id: S24
title: Document the Android app
layer: docs
points: 1
depends_on: [S23, S07, S02, S25]
touches: [Android/README.md, README.md, DECISIONS.md]
status: todo
branch:
started:
completed:
actual_points:
review_fixes:
blocked_reason:
---

# S24 — Document the Android app

## Goal
Someone opening the repo can build, test and understand the Android app, and the decisions made during the port are on record.

## Context
- `Android/README.md` was started by S01 with install and build commands. The root `README.md` has a "Layout" section listing `Engine/` and `App/`. `DECISIONS.md` is the running record of non-obvious choices, written as bold-lead paragraphs under section headings.
- Decisions to record under a new "Android" section in `DECISIONS.md`:
  - A hand port in Kotlin rather than compiling Swift or adopting Kotlin Multiplatform, and why the conformance fixture makes that safe.
  - Two modules (`:engine`, `:app`) and feature folders, not a module per feature.
  - Online play is by link only; no Game Center equivalent, no live rooms, and why (no server to depend on; cross-play with iPhone).
  - The golden fixture (`Engine/Sources/GoldenFixtures`) and what it pins.
  - Compose tests run under Robolectric because the toolchain has no emulator; on-device testing is deferred.
  - Android reads the resume snapshot that iOS only writes; the corrected teaching page 4; the link error alert at app level.
  - Deferred: `https://` App Links, Play Store release, sound, dark theme, tablets and landscape, on-device instrumented tests.
- Also note, under a "Found while porting" heading, the iOS issues this plan uncovered and did not fix: resume never read; match errors never shown; stale teaching page 4; the stale counts in `Web/README.md`.
- Read the merged code before writing. Describe what exists, not what was planned; run every command you document.

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
- `Android/README.md`: what it is, toolchain install, build / test / lint / release commands, module and folder layout, how the engine is verified (conformance fixture, golden fixture, how to regenerate both), how to run on a device with `adb install`.
- Root `README.md`: add `Android/` to the Layout section and a short "Android" paragraph pointing at `Android/README.md`.
- `DECISIONS.md`: the Android section and the "Found while porting" list.

## Out of Scope
- Any code change.
- Fixing the iOS or Web issues listed.

## Acceptance Criteria
- [ ] Every command in `Android/README.md` was run and succeeds from a clean checkout.
- [ ] Test counts quoted in the docs match the real output.
- [ ] No code file changes in this slice.

## Verification
```bash
source Android/scripts/env.sh
cd Android && ./gradlew clean :engine:test :app:testDebugUnitTest :app:lintDebug ktlintCheck :app:assembleDebug
cd .. && git diff --stat plan/android-rewrite...HEAD -- . ':!Android/README.md' ':!README.md' ':!DECISIONS.md' ':!plans' | /usr/bin/grep -c . || true   # expect 0
```

## Estimate Rationale
1: writing only.

## Actuals
- **Actual points:**
- **What took longer / shorter than expected:**
- **Surprises / unknowns that surfaced:**
- **Review fixes applied:**

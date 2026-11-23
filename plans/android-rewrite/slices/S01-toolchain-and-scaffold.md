---
id: S01
title: Install the Android toolchain and scaffold the Gradle project
layer: infra
points: 3
depends_on: []
touches: [Android/settings.gradle.kts, Android/build.gradle.kts, Android/gradle.properties, Android/gradle/, Android/gradlew, Android/gradlew.bat, Android/scripts/, Android/.gitignore, Android/.editorconfig, Android/engine/build.gradle.kts, Android/engine/src/main/kotlin/com/charlesbarton/bananachess/engine/EngineInfo.kt, Android/engine/src/test/kotlin/com/charlesbarton/bananachess/engine/EngineInfoTest.kt, Android/app/build.gradle.kts, Android/app/proguard-rules.pro, Android/app/src/main/AndroidManifest.xml, Android/app/src/main/res/, Android/app/src/main/kotlin/com/charlesbarton/bananachess/MainActivity.kt, Android/app/src/test/kotlin/com/charlesbarton/bananachess/ScaffoldTest.kt, Android/README.md]
status: in_progress
branch: slice/android-rewrite/S01
started: 2026-11-23T12:46
completed:
actual_points:
review_fixes:
blocked_reason:
---

# S01 — Install the Android toolchain and scaffold the Gradle project

## Goal
Make this Mac able to build Android, and create an empty two-module project that builds, tests and lints green. Every later slice builds on it.

## Context
- This Mac has no JDK and no Android SDK. Homebrew is installed. Charles approved installing the toolchain with Homebrew, command-line tools only: no Android Studio and no emulator.
- Use Homebrew **formulae and casks that need no `sudo`**: `brew install openjdk@17` (a formula; do not use the `temurin` cask, whose installer asks for a password) and `brew install --cask android-commandlinetools`. If either asks for a password, stop and report.
- Then `sdkmanager --licenses` (accept) and install `platform-tools`, the current stable `platforms;android-NN` and matching `build-tools`.
- `brew install gradle` is acceptable only to generate the wrapper (`gradle wrapper`); everything afterwards uses `./gradlew`.
- **Look up current stable versions** of Gradle, the Android Gradle Plugin, Kotlin, the Compose BOM and each library at execution time (official release pages or Maven metadata). Do not trust remembered version numbers. Pin them all in the version catalog.
- This slice has no `Android/scripts/env.sh` to source yet; it creates it.

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
- Install JDK 17 and the Android command-line SDK with Homebrew as described in Context.
- `Android/scripts/env.sh`: exports `JAVA_HOME` (from `brew --prefix openjdk@17`) and `ANDROID_HOME` (the Homebrew command-line tools SDK root), and fails with a clear message if either is missing.
- Gradle wrapper, `settings.gradle.kts` with modules `:engine` and `:app`, Kotlin DSL throughout, `gradle/libs.versions.toml` version catalog.
- `:engine`: `kotlin("jvm")`, JVM toolchain 17, kotlinx-serialization-json, tests on JUnit 5 with `kotlin.test`. The test task passes the system property `conformance.fixture` pointing at `../Web/fixtures/conformance.json.gz` (absolute path resolved from the root project) and uses resources from `engine/src/test/resources`.
- `:app`: Android application, `applicationId` and namespace `com.charlesbarton.bananachess`, `minSdk 26`, current stable `compileSdk`/`targetSdk`, Compose enabled, depends on `:engine`. Portrait-only single `MainActivity` showing a placeholder text.
- Declare in the catalog and wire into `:app` every dependency later slices need, so no later slice edits build files: Compose BOM (ui, foundation, material3, ui-tooling-preview, ui-test-junit4, ui-test-manifest), activity-compose, lifecycle-viewmodel-compose, lifecycle-runtime-compose, navigation-compose, datastore-preferences, kotlinx-coroutines (core, android, test), kotlinx-serialization-json, JUnit 4, Robolectric.
- Unit tests in `:app` run on the JVM with Robolectric (`testOptions.unitTests.isIncludeAndroidResources = true`).
- ktlint through a Gradle plugin with a root `ktlintCheck`/`ktlintFormat`, plus `.editorconfig`. Android lint with `warningsAsErrors = true` and `abortOnError = true`.
- `Android/.gitignore` for `build/`, `.gradle/`, `local.properties`, `*.keystore`, `.kotlin/`.
- One trivial passing test per module, and a short `Android/README.md` with the install and build commands.

## Out of Scope
- Any game code (S04 onward).
- CI (S02).
- Release signing, minification and the app icon (S23).

## Acceptance Criteria
- [ ] `java -version` reports 17 after sourcing `Android/scripts/env.sh`, and `sdkmanager --list_installed` shows platform-tools, a platform and build-tools.
- [ ] `./gradlew :engine:test :app:testDebugUnitTest :app:lintDebug ktlintCheck :app:assembleDebug` succeeds from a clean checkout.
- [ ] A debug APK exists under `Android/app/build/outputs/apk/debug/`.
- [ ] The version catalog pins every version; no version literals in module build files.
- [ ] No file outside `Android/` is changed.

## Verification
```bash
source Android/scripts/env.sh
java -version
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --list_installed
cd Android && ./gradlew clean :engine:test :app:testDebugUnitTest :app:lintDebug ktlintCheck :app:assembleDebug
ls app/build/outputs/apk/debug/*.apk
cd .. && git diff --stat plan/android-rewrite...HEAD -- . ':!Android' | /usr/bin/grep -c . || true   # expect 0
```

## Estimate Rationale
3: nothing is installed, versions must be looked up fresh, and Robolectric plus Compose plus lint-as-errors has to work first time. The two prior infra slices in the estimation log came in under estimate (5 estimated, 3 actual), but both were Xcode projects on a machine that already had the toolchain.

## Actuals
- **Actual points:**
- **What took longer / shorter than expected:**
- **Surprises / unknowns that surfaced:**
- **Review fixes applied:**

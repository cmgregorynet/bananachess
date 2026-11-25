---
id: S23
title: Add the app icon and a release build configuration
layer: infra
points: 2
depends_on: [S22]
touches: [Android/app/build.gradle.kts, Android/app/proguard-rules.pro, Android/app/src/main/res/, Android/app/src/main/AndroidManifest.xml, Android/gradle.properties, Android/scripts/]
status: done
branch: slice/android-rewrite/S23
started: 2026-11-23T22:50
completed: 2026-11-23T23:38
actual_points: 3
review_fixes: release.sh SIGPIPE; stale outputs; icon clip and scale
blocked_reason:
---

# S23 — Add the app icon and a release build configuration

## Goal
A shrunk, optimised release build with a real icon, ready to sign when Charles creates a keystore.

## Context
- S01 created a debug-only build. This slice adds the release side without holding any secret.
- **Icon**: an adaptive icon. Background = `warmPaper` (#E7D8D0); foreground = the banana silhouette from `Android/app/src/main/kotlin/com/charlesbarton/bananachess/art/PieceShapes.kt` on a clay tray, as a vector drawable inside the adaptive-icon safe zone; plus a monochrome layer for themed icons. App label "Banana Chess".
- **Release build type**: R8 minification and resource shrinking on; keep rules for kotlinx.serialization if the engine's serializers need them. Prove the shrunk build still works by running the engine's golden codec test logic against the release variant's unit tests (`:app:testReleaseUnitTest`).
- **Signing**: read `BANANA_KEYSTORE`, `BANANA_KEYSTORE_PASSWORD`, `BANANA_KEY_ALIAS`, `BANANA_KEY_PASSWORD` from Gradle properties or the environment. When they are absent the release build is produced **unsigned** and the build still succeeds. Never create or commit a keystore.
- **Versioning**: `versionName` 0.1 and `versionCode` 1, matching iOS (`README.md`: marketing version 0.1, build 1).
- Backups: exclude `current-match.json` from cloud backup and device transfer rules only if lint asks for explicit rules; otherwise leave the defaults and say so.
- S01 left `versionName "1.0"` in `app/build.gradle.kts` and a placeholder adaptive icon (yellow with a dark square). Change the version to 0.1 and replace the icon. S01 left `android:allowBackup` unset because setting it false raises the `DataExtractionRules` lint error.

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
- Adaptive icon resources (foreground, background, monochrome) and manifest reference.
- Release build type with R8, shrinking and keep rules; optional signing config from properties.
- `Android/scripts/release.sh`: builds `bundleRelease` and `assembleRelease` and prints where the outputs are and whether they are signed.

## Out of Scope
- Creating a keystore, a Play Console listing or uploading anything.
- Store screenshots and descriptions.

## Acceptance Criteria
- [ ] `./gradlew :app:assembleRelease :app:bundleRelease` succeeds with no signing properties set.
- [ ] `:app:testReleaseUnitTest` passes.
- [ ] `:app:lintRelease` is clean.
- [ ] No keystore or password appears in the diff.

## Verification
```bash
source Android/scripts/env.sh
cd Android && ./gradlew :app:assembleRelease :app:bundleRelease :app:testReleaseUnitTest :app:lintRelease ktlintCheck
ls app/build/outputs/apk/release/ app/build/outputs/bundle/release/
cd .. && git diff plan/android-rewrite...HEAD --stat | /usr/bin/grep -ci -E "keystore|\.jks" || true   # expect 0
```

## Estimate Rationale
2: R8 with serialization is the usual place a release build breaks.

## Actuals
- **Estimate vs actual:** 2 → 3. R8 was quick (kotlinx.serialization 1.13's bundled rules cover everything; `proguard-rules.pro` holds only a comment). Making release unit tests exist and pass under AGP 9 + Robolectric took longer, and one flaky 33-minute hang cost time.
- **What shipped:** adaptive icon (warmPaper background, banana on clay tray foreground, monochrome layer for themed icons); release build with R8 + resource shrinking; version 0.1 (1); optional signing from `BANANA_KEYSTORE*` properties/env, unsigned otherwise; `Android/scripts/release.sh` builds APK + AAB and reports signed/UNSIGNED and the mapping file. `android.onlyEnableUnitTestForTheTestedBuildType=false` so `testReleaseUnitTest` exists; a release-only `TestBananaChessApplication` registers `ComponentActivity` for Robolectric (hence `BananaChessApplication` is `open`).
- **R8 proof (inspection):** `$$serializer` classes, `Companion.serializer()`, data-object `INSTANCE`s and serial names for Route/MatchSource/MatchMode/StoredSnapshot survive; engine enum names intact. Not yet run on a device.
- **Review fixes:** release.sh SIGPIPE under pipefail misreported signing; stale outputs cleared; apksigner path from the catalog; bruise layer clipped; tray and banana enlarged after rendering.
- **Outside touches:** `BananaChessApplication.kt` (`open`), `src/testRelease/.../TestBananaChessApplication.kt`.
- **Verification:** merged tree, no cache — assembleRelease, bundleRelease, testReleaseUnitTest, lintRelease, plus the debug suite and engine tests; keystore grep 0.
- **Open for Charles:** icon is a taste call (banana reads slightly flat at launcher size). Smoke-test the shrunk release on a device: CPU game, kill process, resume, open a link. Possible latent DataStore/Robolectric flake in `ScaffoldTest.skipTheIntroduction` (one hang, not reproduced in 5 runs).

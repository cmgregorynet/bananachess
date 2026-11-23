---
id: S02
title: Add the Android CI workflow
layer: infra
points: 1
depends_on: [S01]
touches: [.github/workflows/android.yml]
status: todo
branch:
started:
completed:
actual_points:
review_fixes:
blocked_reason:
---

# S02 — Add the Android CI workflow

## Goal
Run the Android build, tests and lint on every push and pull request that touches the Android project or the files it depends on.

## Context
- `.github/workflows/web.yml` is the existing pattern: path-filtered, a single `check` job on `ubuntu-latest`.
- The engine tests read `Web/fixtures/conformance.json.gz`, so the workflow must also trigger on changes to that file.
- Use `actions/setup-java` (Temurin 17), `android-actions/setup-android` or the preinstalled SDK, and `gradle/actions/setup-gradle` for caching. Check current major versions of each action.
- The SDK packages CI must have are exactly the ones in `Android/README.md`: `platform-tools`, `platforms;android-37.0` (lint reads it), `platforms;android-37.2`, `build-tools;37.0.0`.

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
- `.github/workflows/android.yml`: triggers on push to `main` and on pull requests, filtered to `Android/**`, `Web/fixtures/**` and the workflow file; plus `workflow_dispatch`.
- One job: checkout, JDK 17, Gradle cache, then `./gradlew :engine:test :app:testDebugUnitTest :app:lintDebug ktlintCheck :app:assembleDebug` with working directory `Android`.
- Upload test reports as an artifact when the job fails.

## Out of Scope
- Deployment or Play Store upload.
- Changing `web.yml`.

## Acceptance Criteria
- [ ] The workflow file is valid YAML and names the same Gradle tasks as Verification below.
- [ ] It needs no secrets.
- [ ] Build and existing tests green locally.

## Verification
```bash
python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/android.yml'))" 2>/dev/null || ruby -ryaml -e "YAML.load_file('.github/workflows/android.yml')"
/usr/bin/grep -n "gradlew" .github/workflows/android.yml
source Android/scripts/env.sh
cd Android && ./gradlew :engine:test :app:testDebugUnitTest :app:lintDebug ktlintCheck :app:assembleDebug
```

## Estimate Rationale
1: one workflow file following an existing pattern.

## Actuals
- **Actual points:**
- **What took longer / shorter than expected:**
- **Surprises / unknowns that surfaced:**
- **Review fixes applied:**

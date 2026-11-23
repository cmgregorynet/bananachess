---
id: S18
title: Add blend bursts, splatter, piece transitions and haptics
layer: ui
points: 3
depends_on: [S17]
touches: [Android/app/src/main/kotlin/com/charlesbarton/bananachess/art/BlendEffects.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/AndroidMatchHaptics.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/BoardView.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/MatchScreen.kt, Android/app/src/test/kotlin/com/charlesbarton/bananachess/art/BlendEffectsTest.kt, Android/app/src/test/kotlin/com/charlesbarton/bananachess/match/MatchHapticsTest.kt]
status: todo
branch:
started:
completed:
actual_points:
review_fixes:
blocked_reason:
---

# S18 — Add blend bursts, splatter, piece transitions and haptics

## Goal
The juice: fruit bursts into droplets, sometimes splatters the screen, and the phone taps back.

## Context
- iOS sources: `App/BananaChess/Art/BlendEffects.swift` (304 lines), `App/BananaChess/Match/Haptics.swift`. Burst and splatter **data** already exists in `MatchUiState` (S15); this slice draws it and retires it.
- **Droplets**, generated from the effect's seed with the engine's `SplitMix64`: burst = 11 droplets evenly spaced with ±0.30 rad jitter, distance 0.55–1.15, size 0.13–0.30, spin ±1.2; splatter = 34 droplets, ±0.45 rad jitter, distance `rand(0.05…1)^1.7 × 0.95`, size 0.015–0.085, spin ±2. Droplet shape: 9 lobes at radius × rand(0.58…1) joined by quadratic curves through the midpoints.
- **Burst** (`:104-181`): a ring stroke (tint 0.5, width 0.09 × side, scale 0.2 + 1.35 × ring, alpha (1 − ring) × 0.85), a blurred glow (tint 0.4), and the droplets (offset = direction × distance × spread, rotation spin × spread, scale 1 − 0.5 × spread, alpha pulp). Curves: `spread` springs to 1 over 0.55 s; `pulp` reaches 1 in 0.06 s, holds 0.16 s, fades over 0.36 s; `ring` eases to 1 over 0.5 s. Tint is `Palette.wash(kind)`. The burst is removed 750 ms after it appears.
- **Splatter** (`:188-258`): covers the whole screen, multiply-blended, centred on the square where the blend happened; `reach` springs to 1 over 0.26 s then holds 1.2 s; `ink` rises to 0.5 in 0.14 s, holds 0.42 s, fades over 0.9 s. Removed after 1,700 ms.
- **Piece removal** (`:263-286`): a blended piece shrinks to 0.22, rotates 150° and fades; a new piece appears from scale 0.4.
- **Reduced motion** (system animator duration scale = 0): no droplet travel (a brief tint fade instead), calm piece transitions (scale 0.7 + fade), no splatter spring.
- **Haptics** (`Haptics.swift`), through `View.performHapticFeedback` constants so no vibrate permission is needed, honouring the system haptic setting: selection → a light tick; moved → light; blenderBattle → heavy; blenderDestroyed / smoothie → a firm reject-style tap; restored / bananaReturned → soft; blended → medium; delivered / smoothiePoured / gameEnded → a confirm pattern. Choose the closest available constant for the device's API level and fall back gracefully on API 26.
- There is no sound in this game.
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
- `art/BlendEffects.kt`: droplet generation, `BlendBurstView`, `SplatterView`, the pulped enter/exit transitions.
- Draw bursts above the board in `BoardView` and the splatter above everything in `MatchScreen`; retire each after its lifetime through the view model.
- `AndroidMatchHaptics` implementing S15's `MatchHaptics`, provided to the view model from the screen.
- Tests: droplet generation is deterministic per seed and within the stated ranges; a burst is retired after 750 ms and a splatter after 1,700 ms of virtual time; the event → haptic mapping; under reduced motion no splatter spring runs.

## Out of Scope
- Changing when effects are spawned (S15 owns that).
- Sound.

## Acceptance Criteria
- [ ] Blending a fruit in a Compose test produces a burst node that disappears after its lifetime.
- [ ] With `BlendEffectPolicy.Always` a splatter appears, and with `Never` it does not.
- [ ] The app declares no `VIBRATE` permission.
- [ ] Tests added; build, lint and existing tests green.

## Verification
```bash
source Android/scripts/env.sh
cd Android && ./gradlew :app:testDebugUnitTest :app:lintDebug ktlintCheck :app:assembleDebug
cd .. && /usr/bin/grep -c "VIBRATE" Android/app/src/main/AndroidManifest.xml || true   # expect 0
```

## Estimate Rationale
3: animation curves to match by eye, and haptic constants that differ by API level.

## Actuals
- **Actual points:**
- **What took longer / shorter than expected:**
- **Surprises / unknowns that surfaced:**
- **Review fixes applied:**

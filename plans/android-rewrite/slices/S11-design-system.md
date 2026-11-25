---
id: S11
title: Build the paper-and-watercolour design system
layer: ui
points: 3
depends_on: [S01]
touches: [Android/app/src/main/kotlin/com/charlesbarton/bananachess/art/Palette.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/art/Paper.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/art/PaperTheme.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/art/PaperButton.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/art/WatercolorWash.kt, Android/app/src/test/kotlin/com/charlesbarton/bananachess/art/]
status: done
branch: slice/android-rewrite/S11
started: 2026-11-23T12:54
completed: 2026-11-23T13:00
actual_points: 3
review_fixes: deprecated paint API; stronger grain pixel test
blocked_reason:
---

# S11 — Build the paper-and-watercolour design system

## Goal
The look of the game as reusable Compose pieces: palette, paper grain, washi panels, watercolour wash, typography and buttons.

## Context
- iOS sources: `App/BananaChess/Art/Palette.swift`, `App/BananaChess/Art/Paper.swift`, and `PaperButtonStyle` in `App/BananaChess/Match/MatchView.swift:350-372`.
- **Palette** (sRGB hex): warmPaper E7D8D0, cream F8E2D5, driftwood F1D7C5, sand BDA99D, fogWater B1B3B3, slate BEB7BB, ink 61524D, inkSoft = ink at 0.55, inkFaint = ink at 0.22, sage 6E7A4E, sageLight 8A9469, amber E8A24A, bananaWash DFC77E, strawberryWash C4776F, pineappleWash D1AC63, blenderGlass D5DAD6, squareLight F4E4D8, squareDark E0CCBE, clayLight EFD3B6, clayDeep CFA987, indigoLight 8398B5, indigoDeep 556C90, top panel tint D9E1EC. Amber is the only accent. No pure black anywhere; the darkest value is ink.
- **Grain**: a 128×128 tile with 3,200 speckles, each 0.5–1.9 px square, grey 0.35 at alpha 0.02–0.08, placed by a deterministic LCG seeded `0x5EED1234ABCD0001`, tiled and multiply-blended. Opacity by use: default 0.5, board 0.4, panels 0.35, trays 0.3, background 0.6. Generate the tile once and cache it.
- **PaperBackground**: warmPaper; radial gradient cream 0.9 → clear (radii 40 → 460); linear gradient clear → sand 0.28 top to bottom; grain 0.6.
- **WashiPanel**: rounded rect radius 18, cream fill, grain 0.35, stroke 1.4 inkSoft or 2.5 amber when accented.
- **WatercolorWash(shape, tint)**: gradient fill top-start → bottom-end at tint alpha 0.72 / 1.0 / 0.92; a pooled edge (tint 0.55, blur 2.2, offset 1.2, 2.0, masked to the shape); ink 0.85 stroke width 1.6 with round caps and joins.
- **Buttons**: capsule; primary = amber 0.35 fill and 2 dp amber stroke; secondary = cream fill and 1.2 dp inkSoft stroke; pressed = 0.7 alpha and 0.97 scale.
- **Type**: a serif family throughout (the platform serif; no bundled font). Light theme only; ignore the system dark setting. Sizes in `sp` so text scales with the user's font size.
- `PieceKind` and seat types come later (S12); this slice has no engine dependency.

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
- `Palette` object with every colour above and `PlayerSeat { Bottom, Top }` with `panelTint(seat)`.
- `PaperTheme` composable wrapping Material 3 with a fixed light colour scheme and serif typography.
- `PaperGrain` modifier/composable, `PaperBackground`, `WashiPanel`, `WatercolorWash`.
- `PaperButton` (primary and secondary).
- `@Preview`s for each component.
- Tests: palette values match the hex list; the grain tile is deterministic (same pixels on two generations); a Robolectric Compose test that a `PaperButton` shows its label, is clickable, and exposes the button role.

## Out of Scope
- Piece drawings (S12).
- Screens (S13 onward).

## Acceptance Criteria
- [ ] Every palette colour equals its hex in a unit test.
- [ ] No `Color.Black` and no dark colour scheme in `:app`.
- [ ] Tests added; build, lint and existing tests green.

## Verification
```bash
source Android/scripts/env.sh
cd Android && ./gradlew :app:testDebugUnitTest :app:lintDebug ktlintCheck :app:assembleDebug
cd .. && /usr/bin/grep -rn --include='*.kt' -E "Color\.Black|darkColorScheme" Android/app/src/main | /usr/bin/grep -c . || true   # expect 0
```

## Estimate Rationale
3: five visual components and a deterministic texture; no logic risk.

## Actuals
- **Actual points:** 3. The estimate was 3.
- **What took longer / shorter than expected:** Slightly shorter. Robolectric's native graphics rendered real pixels first try, so the components have pixel-level tests and not only semantics tests.
- **Surprises / unknowns that surfaced:**
  - Nothing was looked at on a device or rendered preview. Fidelity to iOS is checked only by pixel tests (the panel is grained cream; a wash stays inside its shape).
  - The pooled-edge blur uses `BlurMaskFilter` at 2.2 dp. Whether it matches SwiftUI's blur in softness is unverified.
  - The grain tile is rasterised at the rounded screen density, so pixels are not byte-identical to the iOS tile; the speckle sequence is.
  - `PaperButton` has no ripple and a disabled alpha of 0.45 (a choice; iOS has no explicit value).
  - `Palette.wash(kind)` is left for S12 because it needs the engine's `PieceKind`.
- **Review fixes applied:** Replaced a deprecated paint API that failed to compile; strengthened the panel pixel test to assert grain is present; reworded an unverified comment.

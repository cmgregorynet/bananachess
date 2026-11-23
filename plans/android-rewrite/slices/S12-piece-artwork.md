---
id: S12
title: Draw the pieces: fruit, blenders, trays and the golden halo
layer: ui
points: 3
depends_on: [S11, S04]
touches: [Android/app/src/main/kotlin/com/charlesbarton/bananachess/art/PieceShapes.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/art/PieceArtwork.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/art/ArtCatalog.kt, Android/app/src/test/kotlin/com/charlesbarton/bananachess/art/PieceArtworkTest.kt]
status: todo
branch:
started:
completed:
actual_points:
review_fixes:
blocked_reason:
---

# S12 — Draw the pieces: fruit, blenders, trays and the golden halo

## Goal
Every piece drawn as vectors exactly as on iOS, with ownership readable without colour.

## Context
- iOS sources: `App/BananaChess/Art/PieceShapes.swift` (151 lines of cubic Béziers in unit coordinates; port the numbers directly to `Path.cubicTo` scaled by size) and `App/BananaChess/Art/PieceArtwork.swift` (229 lines).
- Uses S11's `Palette`, `WatercolorWash`, `PaperGrain`, `PlayerSeat` and the engine's `PieceKind`.
- Shapes: banana, strawberry, pineapple, pineapple crown, strawberry calyx, blender jar, blender base, blender lid.
- Layers back to front (`PieceArtwork.swift:36-55`): golden halo if golden; tray if shown; then the drawing, padded 0.13 × side with a tray and 0.02 without.
- **Tray** (`:59-83`): circle with a radial gradient (bottom seat clayLight → clayDeep, top seat indigoLight → indigoDeep) from the top-start, end radius 0.9 × side; ink 0.75 border 0.03 × side; **top seat only**: an inner cream 0.5 ring, 0.018 × side wide, inset 0.075 × side; grain 0.3 clipped to the circle. The second ring is what keeps the sides apart in greyscale.
- **Golden halo** (`:89-108`): amber 0.75 blurred 0.16 × side at scale 1.26, plus amber 0.4 blurred 0.05 × side at scale 1.08; breathes between scale 0.97 and 1.06 and alpha 0.72 and 1 over 1.8 s, reversing forever. No animation when the system animator scale is 0 (reduced motion).
- **Pieces** (`:122-212`): banana = wash + a sand 0.35 bruise; strawberry = wash + six cream 0.85 seed ellipses + sageLight calyx; pineapple = wash (amber when golden) + ink 0.28 crosshatch (0.8 width, step 0.16 × width) masked to the body + sage crown; blender = base (sand bottom seat / slate top) + jar (blenderGlass 0.9) + two blade strokes forming an X (ink 0.6, 1.6, round) + lid (driftwood bottom / fogWater top).
- `ArtCatalog`: prefer a drawable named `piece_<kind>_<p1|p2>` when one exists, otherwise the vectors. None exist today; the lookup is the seam for future painted sprites.
- Pieces are decorative: no semantics of their own (the square that holds them carries the label).

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
- `PieceShapes.kt`: the eight `Path` builders.
- `PieceArtwork(kind, seat, isGolden, showsTray, modifier)`.
- `ArtCatalog.sprite(kind, seat)` with the drawable lookup and vector fallback.
- `@Preview` showing all four kinds for both seats, with and without tray, plus a golden pineapple.
- Tests: each shape's path is non-empty and stays inside its unit bounds; `ArtCatalog` falls back to vectors when no drawable exists; a Robolectric Compose test that `PieceArtwork` adds no semantics node of its own.

## Out of Scope
- Blend bursts and splatter (S18).
- The board (S16).

## Acceptance Criteria
- [ ] All eight shapes render in the preview composable without exceptions under a Robolectric test.
- [ ] The top-seat tray draws the inner ring and the bottom-seat tray does not (asserted through a small testable description of the tray layers, not pixels).
- [ ] Tests added; build, lint and existing tests green.

## Verification
```bash
source Android/scripts/env.sh
cd Android && ./gradlew :app:testDebugUnitTest :app:lintDebug ktlintCheck :app:assembleDebug
```

## Estimate Rationale
3: eight Bézier shapes and layered drawing to transcribe carefully.

## Actuals
- **Actual points:**
- **What took longer / shorter than expected:**
- **Surprises / unknowns that surfaced:**
- **Review fixes applied:**

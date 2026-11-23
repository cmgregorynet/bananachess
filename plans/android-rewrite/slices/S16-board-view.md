---
id: S16
title: Build the board composable
layer: ui
points: 3
depends_on: [S15, S12]
touches: [Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/BoardView.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/SquareView.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/DestroyedBlenderMarker.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/FlashPill.kt, Android/app/src/test/kotlin/com/charlesbarton/bananachess/match/BoardViewTest.kt]
status: todo
branch:
started:
completed:
actual_points:
review_fixes:
blocked_reason:
---

# S16 — Build the board composable

## Goal
The 6×6 board: squares, pieces that travel between them, legal-move dots, wrap seams, buy-back markers and the flash pill, fully usable with TalkBack.

## Context
- iOS source: `App/BananaChess/Match/BoardView.swift` (383 lines). State comes from S15's `MatchUiState`; drawing uses S11 and S12.
- Square board, cell = side / 6. Layers (`:15-37`): ground (squareLight + grain 0.4), squares, destroyed-blender markers, pieces — clipped to a 6 dp rounded rect; flash on top, unclipped; an outer border (radius 10, ink 0.7, 2 dp, outset 6).
- **Squares are laid out in a real grid** (rows of cells), never positioned by offset. This is a recorded accessibility decision (`DECISIONS.md` §Architecture, "Squares are laid out, not offset"): each square is one clickable node with `testTag` `square_<row>_<col>` in **engine coordinates** (unaffected by rotation) and the content description from `SquareDescription`.
- **Square** (`:318-358`): dark when (row + column) is even; delivery-row hatching (diagonal lines, 7 dp step; bottom seat sand 0.75 at 1.2, top seat fogWater 0.75 at 0.8); 0.7 dp ink 0.18 border; blender home = dashed circle (ink 0.45, 1.4, dash 3/3, padding 6); selected origin = amber 0.16 fill; legal move = 12 dp amber 0.85 dot with a 1 dp ink 0.4 ring.
- **Wrap seams** (`:59-87`): amber 0.55 dashed lines (2.4 wide, round caps, dash 5/5) inset 1.2 dp; left and right when columns wrap, top and bottom too when rows wrap.
- **Pieces** (`:134-156`): drawn in a separate layer positioned by animated offset so they slide between squares; not clickable and hidden from accessibility; a selected piece scales 1.08 with a 3 dp amber ring at 0.94 × cell. Movement spring ≈ response 0.34 / damping 0.78; returns (restore, banana return) ≈ 0.5 / 0.62; a short fade when reduced motion is on.
- **DestroyedBlenderMarker** (`:264-304`), 0.82 of the cell: tray tint 0.28 circle, greyscale ghost blender (alpha 0.4 if purchasable, else 0.28), dashed ring (amber if purchasable), and either a "−3" pill (purchasable) or a countdown number.
- **Flash pill** (`:222-242`): small serif semibold text in a cream capsule with a 1.5 dp amber border, offset (+0.5 cell, −0.15 cell) from its square, fading in with an 8 dp rise.
- **Rotation** (`:248-259`): when `isRotated`, the board is drawn turned 180° so the local player's pieces are at the bottom; piece drawings and text stay upright.

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
- `BoardView(state, onSquareTap, modifier)` and the pieces listed above.
- Semantics: one node per square with role button, tag and description; pieces, seams, markers and the flash are excluded from the accessibility tree.
- Robolectric Compose tests: 36 square nodes exist; tapping calls back with engine coordinates, also when rotated; a selected fruit's destination square description contains "legal move"; a blender home says "blender home"; on a Sides board a fruit at (3,0) shows (3,5) as a legal move and on Walls it does not; a purchasable spawn says "buy it back".

## Out of Scope
- Scoreboards, controls and dialogs (S17).
- Bursts and splatter (S18).

## Acceptance Criteria
- [ ] The board renders the standard opening under Robolectric with all 36 squares reachable by tag.
- [ ] Square tags use engine coordinates in both orientations.
- [ ] Tests added; build, lint and existing tests green.

## Verification
```bash
source Android/scripts/env.sh
cd Android && ./gradlew :app:testDebugUnitTest :app:lintDebug ktlintCheck :app:assembleDebug
```

## Estimate Rationale
3: custom drawing plus the accessibility structure that the smoke tests depend on.

## Actuals
- **Actual points:**
- **What took longer / shorter than expected:**
- **Surprises / unknowns that surfaced:**
- **Review fixes applied:**

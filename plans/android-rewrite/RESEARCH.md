---
slug: android-rewrite
title: Banana Chess for Android (native Kotlin rewrite)
created: 2026-11-23
depth: full
---

# Research: Banana Chess for Android (native Kotlin rewrite)

## Question
What exactly has to be reproduced to ship Banana Chess as a native Android app that plays the same game as iOS, and what is missing on this machine and in this repo before that work can start?

## What Exists Today

Three implementations of one game live in this repo. The Swift engine is the specification; the other two follow it.

- **Entry points / flows:**
  - `Engine/`: a pure Swift package (about 3,540 lines) with no UI imports. Everything that changes a game goes through `RulesEngine.apply` (`Engine/Sources/BananaChessEngine/RulesEngine.swift:283`), which returns the new state and a list of events. Rules version 18 (`RulesEngine.swift:67`).
  - `App/`: the SwiftUI iPhone app (about 5,000 lines). Root is `HomeView` (`App/BananaChess/BananaChessApp.swift:10-27`); routes are local, computer, online, by-link, cloud (`App/BananaChess/Home/HomeViewModel.swift:7-20`).
  - `Web/`: a TypeScript port of the engine plus a Cloudflare Worker room server (`Web/src/engine/*`, `Web/server/*`).

- **Data model & persistence:**
  - `GameState` (`Engine/Sources/BananaChessEngine/GameState.swift:28`): pieces sorted by id, active player, scores (may go negative), status, turn number, action history, rule set, board setup, board wrap, `boardHistory: [UInt64]`, blender battles flag, wildcard progress.
  - Actions and events are enums with associated values (`GameAction.swift:3-10`, `GameEvent.swift:3`). `GameState.applying(event)` replays presentation only (`GameState+Events.swift:7`).
  - iOS settings are six UserDefaults keys (`HomeViewModel.swift:52-77`).
  - iOS writes a resume snapshot to `current-match.json` after every move (`App/BananaChess/Match/MatchSnapshotStore.swift`), but `load()` is never called, so resume does not work on iOS today.

- **Services / APIs / backend:**
  - **Link play (the format Android will use):** `MatchPayload` (`Engine/Sources/BananaChessEngine/Online/MatchPayload.swift:5-32`) is `{schemaVersion: 16, rulesVersion: 18, matchID, moveNumber, state, lastMove?}`, encoded by `JSONEncoder` with sorted keys (`:107-114`) and refused on any version mismatch (`:127-144`). `MatchLink` (`Online/MatchLink.swift`) wraps it as `bananachess://m/1/<blob>`, where the blob is the JSON compressed with `NSData.compressed(using: .zlib)` and base64url-encoded without padding (`:73-96`). `MatchAuthority` (`Online/MatchAuthority.swift:54-94`) separates preparing a turn from committing it.
  - **Game Center and CloudKit** (`App/BananaChess/Online/*`): Apple-only. CloudKit has never run and is paused (`docs/cloudkit.md:94-110`).
  - **Cloudflare room server** (`Web/server/room.ts`): live rooms over WebSocket, server-authoritative, but it speaks its own JSON (`Web/src/engine/wire.ts`), keeps matches only in memory, and no deployed address is recorded in the repo. Not used by this plan.

- **UI surfaces (what the Android app must reproduce):**
  - **Home** (`App/BananaChess/Home/HomeView.swift`): title art, menu buttons, CPU options sheet (Easy / Medium / Hard / Brutal, "You move first"), Board picker (Standard / Blendy / Random), Edges picker (Walls / Sides / All), "Wildcard rules" and "Blender battles" checkboxes, "How to play".
  - **Match** (`App/BananaChess/Match/MatchView.swift`, `BoardView.swift`, `MatchViewModel.swift`): two scoreboards, turn indicator, 6×6 board (rotated 180° when the bottom player is player two), legal-move dots, wrap seams, destroyed-blender markers with buy-back price, flash pill, self-blend confirmation, restart confirmation, result overlay, wildcard trackers.
  - **Event timeline** (`MatchViewModel.swift:340-433`): events animate one at a time with fixed beats (moved 280 ms, blended 420 ms, battle 900 ms, and so on; 90 ms under Reduce Motion).
  - **Rules sheet and four-page teaching overlay** (`App/BananaChess/Rules/RulesView.swift`).
  - **Art** (`App/BananaChess/Art/*`): a fixed palette (`Palette.swift`), procedural paper grain and watercolour wash (`Paper.swift`), pieces as hand-authored Bézier paths in unit coordinates (`PieceShapes.swift`), tray and golden halo (`PieceArtwork.swift`), blend burst and splatter (`BlendEffects.swift`). No bitmap assets exist. Light mode only.
  - **Haptics** (`App/BananaChess/Match/Haptics.swift`). There is no sound.
  - **Accessibility:** every square is one labelled button (`MatchViewModel.swift:886-930`); identifiers `square_<row>_<col>`, `turnIndicator`, `sendLink`.

- **Tests & tooling (how to build, test, lint):**
  - Engine: `cd Engine && DEVELOPER_DIR=/Applications/Xcode-26.5.0.app/Contents/Developer swift test` (171 `@Test` functions).
  - Web: `cd Web && npm test` (about 131 tests) and `npm run typecheck`.
  - **Conformance fixture:** `Web/fixtures/conformance.json.gz` holds 108 complete games (13,829 steps) written by the Swift executable `Engine/Sources/ConformanceFixtures/main.swift`. Each step records the action string, event strings, the board after, active player, scores and turn number; the first 8 steps of each game also list every legal action. It covers 2 rule sets × 3 setups × 3 edge settings × battles on/off × 3 drivers. The web test replays it (`Web/test/conformance.test.ts:59-108`) but does not compare events.
  - CI: `.github/workflows/web.yml` (Node only). No Android workflow.
  - Android: nothing exists. No `Android/` directory, no Gradle files.

## Infrastructure & Backend Gaps

| Gap | Where | Blocks | Severity |
|---|---|---|---|
| No JDK and no Android SDK on this Mac (`java -version` fails; `~/Library/Android` absent; `gradle`, `adb`, `sdkmanager` not found) | machine | every Android build and test | blocker |
| No Android project: no Gradle wrapper, modules, version catalog, lint or formatting setup | repo | all code slices | blocker |
| No Android CI | `.github/workflows/` | keeping the port green after merge | medium |
| No golden data for the link format. No test or fixture captures a `MatchPayload` as JSON bytes or a real `MatchLink` URL, so a Kotlin codec cannot be checked against Swift | `Engine/` | iOS ↔ Android link play | high |
| No golden data for the hash keys or the CPU evaluation. `positionKey` seeds battle dice and `boardKey` drives the repetition rule; the fixture checks them only indirectly | `Engine/` | confidence in the CPU port and in dice agreement across devices | medium |
| The conformance fixture's events are never checked by any consumer | `Web/test/conformance.test.ts` | nothing; the Kotlin harness can simply check them | low |
| No hosted domain for `https://` links; only the `bananachess://` scheme works | `App/BananaChess/Online/MatchLinkSettings.swift:12` | Android App Links (out of scope) | low |

## Constraints

- **The Swift engine is the specification.** A port must reproduce it exactly, including:
  - SplitMix64 (`CPU/SeededGenerator.swift:10`), Swift's `next(upperBound:)` (Lemire's method, not modulo) and its forward Fisher-Yates `shuffled` for the random board (`GameSetup.swift:118`);
  - FNV-1a 64-bit `boardKey` and `positionKey` (`GameState.swift:232`, `:259`);
  - battle dice derived from `positionKey` with unsigned 64-bit modulo (`RulesEngine.swift:447-474`);
  - canonical action ordering and the action description strings (`Move.swift:16-22`, `GameAction.swift:23`).
- **Link compatibility with iOS:** the JSON must match Swift's synthesized `Codable` output: sorted keys, nil fields omitted, `/` escaped as `\/`, enum cases as `{"move":{"_0":{…}}}` / `{"inProgress":{}}`, `PieceID` as a bare string, `boardHistory` as unsigned 64-bit JSON numbers. Exact schema 16 and rules 18 are required.
- **Decisions the port must honour** (`DECISIONS.md`): one transition path and event replay (`:444-488`); self-blend needs a confirmation and a legal destination wins the tap (`:26`); occupied squares announce whether they are legal targets (`:33`); old matches are refused, not migrated (`:46`); the rule set lives in the state (`:57`); blenders never cross an edge (`:278-310`); buy-back has no affordability check (`:341-362`); squares are laid out, not offset (`:444-488`); undo is debug-only; light mode only; no push notifications (`:597-607`).
- **Engineering DNA, translated to Android:** feature-first folders, single app module plus a pure engine module (mirrors the Swift package split), ViewModels own observable state, constructor injection with default production instances, no DI container, no repository wrappers, protocols only where a test needs them, minimal dependencies, isolation stated in code (explicit dispatchers, never implied).
- **IP boundary:** private repo; nothing here is copied elsewhere.

## Options Considered

**How to get the engine onto Android**

| Option | Cost | Buys |
|---|---|---|
| Kotlin port in a pure JVM module, verified by the conformance fixture | about 3,500 lines ported by hand | native debugging, no native toolchain, runs in plain JVM unit tests, same approach that already works for the web port |
| Compile the Swift engine for Android | Swift-on-Android toolchain, JNI bridge, larger APK, harder debugging | one source of truth |
| Kotlin Multiplatform shared with iOS | rewriting the iOS engine dependency too | one source of truth eventually |

Recommendation: the Kotlin port. The fixture already makes a hand port safe, and the other two options add toolchain risk to a rewrite whose point is a clean native app.

**UI test strategy without an emulator**

Charles chose command-line tools without an emulator. Compose UI tests therefore run on the JVM under Robolectric, which needs no device. On-device instrumented tests are out of scope.

**Online scope**

Charles chose play by link. Live rooms through the Cloudflare server are out of scope.

## Risks & Unknowns

- **Apple's `.zlib` framing.** `NSData.compressed(using: .zlib)` is believed to produce raw DEFLATE with no zlib header, which means `Inflater(true)` in Kotlin. Unverified. Retired by S03, which captures real links from Swift.
- **Foundation's sorted-key order and number formatting.** Believed to be plain byte order for these ASCII keys. Retired by the byte-equality check in S10 against S03's golden payloads.
- **Unsigned 64-bit JSON numbers.** `boardHistory` values exceed `Long.MAX_VALUE`. kotlinx.serialization supports `ULong`, but this must be proven on real data (S10).
- **Value semantics.** The Swift engine relies on copy-on-assign everywhere. A Kotlin port with shared mutable lists would corrupt the CPU search. Contained by immutable data classes and the full-game replay.
- **CPU strength on slower phones.** Search is bounded by wall-clock time, so a slow device searches shallower but never hangs. Medium, Hard and Brutal are not reproducible across devices on iOS either.
- **Library versions.** Exact current versions of the Android Gradle Plugin, Kotlin and Compose must be looked up at execution time (S01), not assumed.
- **Parallel Gradle lanes.** Up to five worktrees building at once share one Gradle cache and can use a lot of memory.

## Open Questions for Charles

None that block the plan. Assumptions made, all cheap to change before S01 runs:

- Application id `com.charlesbarton.bananachess` (same as the iOS bundle id).
- Minimum Android 8.0 (API 26); portrait phone layout; light theme only; no sound.
- The Android "Invite by Link" uses the `bananachess://` scheme only, exactly as iOS does today.

## References

- `README.md`, `DECISIONS.md`, `RULES_ADDENDUM.md`, `Web/README.md`, `docs/README.md`, `docs/hosting.md`, `docs/cloudkit.md`
- `~/CharlesOS/KNOWLEDGE/ENGINEERING_DNA.md`, `~/CharlesOS/ENGINES/implementation-plan/SPEC.md`
- `~/CharlesOS/ENGINES/estimation/actuals.jsonl` (14 prior slices, none in this repo)

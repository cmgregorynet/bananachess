---
id: S10
title: Port the match payload codec, match links and match authority
layer: domain
points: 3
depends_on: [S06, S03]
touches: [Android/engine/src/main/kotlin/com/charlesbarton/bananachess/engine/online/, Android/engine/src/test/kotlin/com/charlesbarton/bananachess/engine/online/]
status: done
branch: slice/android-rewrite/S10
started: 2026-11-23T13:16
completed: 2026-11-23T13:28
actual_points: 3
review_fixes: damaged-link and inflate-limit tests made real; no production defects
blocked_reason:
---

# S10 — Port the match payload codec, match links and match authority

## Goal
Read and write the exact link format iOS uses, so an Android phone and an iPhone can play each other by link.

## Context
- Swift sources: `Engine/Sources/BananaChessEngine/Online/MatchPayload.swift`, `MatchLink.swift`, `MatchAuthority.swift`. Do **not** port `OnlineMatchService.swift` or `InMemoryMatchService.swift` (Game Center abstractions) and do **not** use `Web/src/engine/wire.ts` (a different, incompatible format).
- `MatchPayload`: `schemaVersion` (16), `rulesVersion` (18), `matchID`, `moveNumber`, `state`, optional `lastMove {action, playedBy, events}`. Decoding refuses anything but the exact versions, with distinct errors: malformed, newer schema, older schema, incompatible rules.
- **JSON must be byte-identical to Swift's `JSONEncoder` with `.sortedKeys`**: keys sorted, no whitespace, nil fields omitted, `/` escaped as `\/`, `PieceID` as a bare string, raw-value enums as strings, `Position` as `{"column","row"}`, `Scores` and `PlayerPair` as `{"one","two"}`, and enums with associated values in Swift's synthesized form: `{"move":{"_0":{…}}}`, `{"restoreBlender":{"at":{…},"pieceID":"…"}}`, `{"inProgress":{}}`, `{"ended":{"_0":{"win":{"_0":"one","by":"score"}}}}`, `{"draw":{}}`, `{"blenderBattle":{"_0":{…},"at":{…}}}`, other events keyed by their labels. `boardHistory` is an array of unsigned 64-bit JSON numbers larger than `Long.MAX_VALUE`.
- The shapes above are inferred from Swift's documented behaviour. **The golden file is the authority**: where it disagrees with this list, follow the golden file and note the difference in the report.
- Write explicit kotlinx.serialization serializers (or a hand-written `JsonElement` builder) rather than relying on default polymorphism; keep the engine's model classes free of serialization annotations if that is cleaner.
- `MatchLink`: `bananachess://m/1/<blob>` and `https://<host>/m/1/<blob>` both decode; format numbers above 1 are refused. The blob is the payload JSON compressed with Apple's `.zlib`, believed to be **raw DEFLATE** (`java.util.zip.Inflater(true)` / `Deflater(level, true)`), then base64url without padding. Confirm against the golden links. Compressed bytes need not match Swift's; they must round-trip and Swift's must decode. Error messages, verbatim: "That link isn't a Banana Chess match." / "That match was sent from a newer version of Banana Chess." / "That match link is damaged and can't be read."
- `MatchAuthority` (`MatchAuthority.swift:54-94`): `prepareTurn(action)` is pure and returns the next payload, the events and `expectedPredecessor`; `commit` requires the same match id and predecessor; `adopt(remote)` refuses a different match or an older move number.
- Golden data (S03): each sample in `Android/engine/src/test/resources/golden.json.gz` has `payloadJSON`, `link`, `boardKey`, `positionKey`. A loader may already exist at `engine/src/test/.../support/GoldenFixture.kt` (S08); if it does not, write a small one inside `engine/src/test/.../online/`.
- Tests to port: the codec, link and authority tests in `Engine/Tests/BananaChessEngineTests/OnlineTests.swift` (skip the ones about `InMemoryMatchService`).
- **Observed in S03's golden data (facts, not inference):** the link blob is raw DEFLATE; payload JSON is compact with sorted keys; top-level keys are `lastMove` (omitted at move 0), `matchID`, `moveNumber`, `rulesVersion`, `schemaVersion`, `state`; piece keys are `home`, `id`, `kind`, `owner`, `position` (no `position` key when off-board; `respawnTurn` never appears); `restoreBlender` is `{"at","pieceID"}` with no `_0`; `gameEnded` is `{"outcome":{…}}`; `blenderBattle` mixes `_0` and `at`; `boardHistory` holds values above `Long.MAX_VALUE`. The golden file itself contains raw UTF-8 (`→`) and stores each `payloadJSON` as a string.
- **Not pinned by the golden data**, so implement from Swift's synthesized-Codable rules and cover with round-trip tests of your own: `blenderRespawned`, `smoothie`, `turnPassed`, a `draw` outcome, win reasons `bananaSplit` and `blenderama`, a non-nil `respawnTurn`, and `\/` escaping (test with a match id containing `/`).

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
- `engine/online/MatchPayload.kt`, `MatchCodec.kt` (encode to `String`/bytes, decode with typed errors), `MatchLink.kt`, `MatchAuthority.kt`.
- Golden tests: every `payloadJSON` decodes; re-encoding gives the identical string; the decoded state's `boardKey` and `positionKey` equal the golden values; every golden `link` decodes to the same payload; `encode → decode` of a link round-trips.
- Tests for each refusal: wrong schema (newer and older), wrong rules version, malformed JSON, damaged blob, non-match URL, format 2.
- Ported authority tests: prepare is pure, commit advances once, a duplicate commit is refused, a stale remote is refused.

## Out of Scope
- Any networking.
- Android intents and sharing (S21, S22).
- The Cloudflare room protocol.

## Acceptance Criteria
- [ ] All golden payloads re-encode byte-for-byte and all golden links decode.
- [ ] Every refusal case produces its specific error and message.
- [ ] Tests added; build and existing tests green.

## Verification
```bash
source Android/scripts/env.sh
cd Android && ./gradlew :engine:test ktlintCheck
```

## Estimate Rationale
3: the real unknowns of the plan live here (Apple's compression framing, exact JSON bytes, unsigned 64-bit numbers); S03's golden data turns each into a failing test with a visible diff.

## Actuals
- **Actual points:** 3. The estimate was 3.
- **What took longer / shorter than expected:** The codec matched all golden bytes on the first run and raw DEFLATE worked as S03 observed. The test suite (hostile input, long JSON constants) and a Swift probe for shapes the golden file lacks took the time.
- **Surprises / unknowns that surfaced:**
  - Checked in both directions: all 146 golden payloads re-encode byte for byte in Kotlin, and Kotlin-made links for all of them (plus two more) were decoded by the Swift engine and re-encoded identically. The Swift-side check was a one-off and is not committed.
  - Every wire shape the plan called "not pinned" is now pinned against real Swift output (draw, both wildcard win reasons, `turnPassed`, `blenderRespawned`, `smoothie`, a non-nil `respawnTurn`, extreme `boardHistory` values, string escaping). The strings are constants in `SwiftSamples.kt`; the probe that produced them lived only in the scratchpad.
  - Deliberate differences from Swift: versions are checked before the rest of the payload is decoded, so a future schema with a changed shape reports "newer version" where Swift reports "malformed"; Kotlin refuses non-integer numbers; Kotlin re-sorts pieces that arrive unsorted.
  - Hostile-input limits: links over 65,536 characters, inflation past 1 MiB, off-board squares, duplicate piece ids and two pieces on one square are all refused. Not validated: whether a position is honestly reachable, as on iOS.
  - `MatchAuthority` is immutable: `commit` and `adopt` return a new authority.
  - `MatchOptions` now exists in both `engine/online/` and `:app` (`match/MatchMode.kt`). One should go (S21).
- **Review fixes applied:** Replaced invented damaged-link blobs with real zlib- and gzip-wrapped streams; fixed the inflate-bomb test so it reaches the inflate limit. No production defects found.

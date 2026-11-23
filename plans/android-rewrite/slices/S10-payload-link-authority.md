---
id: S10
title: Port the match payload codec, match links and match authority
layer: domain
points: 3
depends_on: [S06, S03]
touches: [Android/engine/src/main/kotlin/com/charlesbarton/bananachess/engine/online/, Android/engine/src/test/kotlin/com/charlesbarton/bananachess/engine/online/]
status: todo
branch:
started:
completed:
actual_points:
review_fixes:
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
- **Actual points:**
- **What took longer / shorter than expected:**
- **Surprises / unknowns that surfaced:**
- **Review fixes applied:**

---
id: S21
title: Add play-by-link match logic
layer: ui
points: 3
depends_on: [S20]
touches: [Android/app/src/main/kotlin/com/charlesbarton/bananachess/link/, Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/MatchViewModel.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/MatchUiState.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/home/HomeViewModel.kt, Android/app/src/test/kotlin/com/charlesbarton/bananachess/link/, Android/app/src/test/kotlin/com/charlesbarton/bananachess/match/LinkPlayTest.kt]
status: todo
branch:
started:
completed:
actual_points:
review_fixes:
blocked_reason:
---

# S21 — Add play-by-link match logic

## Goal
Everything behind correspondence play: start an invitation, open a link someone sent, produce the link for your move. No Android UI yet.

## Context
- iOS sources: `App/BananaChess/Home/HomeViewModel.swift:110-143` (start and open), `App/BananaChess/Match/MatchViewModel.swift:707-720` (link to send) and the `.byLink` mode. The engine side (`MatchPayload`, `MatchCodec`, `MatchLink`, `MatchAuthority`) is S10 in `engine/online/`.
- **How link play works**: a link *is* the whole match. There is no server. You make a move, send a link; your opponent opens it, sees your move animate, moves, and sends one back.
- **Start** (`startLinkMatch`): a new match id (UUID), dealt with the current options, the inviter seated as player two and player one to move, so there is a link to send immediately. Mode `ByLink(matchID, localPlayer = two)`.
- **Open** (`open(url)`): decode with `MatchLink`; the local player is `payload.state.activePlayer`. If the payload has a `lastMove`, the match opens showing the position before it and animates its events, so the opponent's move is seen, not just its result.
- **Link to send** (`linkToSend`): in `ByLink` mode a link exists only when it is the opponent's turn or the match is over; it is the `bananachess://m/1/…` URL for `MatchPayload(matchID, moveNumber = history.count, state = authoritative, lastMove = the move just made)`. Title: "Send the invitation" (no moves yet) / "Send your move" / "Send the final position". Share text: "Your move in Banana Chess." or "Here's how our Banana Chess match finished."
- **Turn indicator** in link play: "Send the link to start" / "Send your move" while a link is waiting to go out; otherwise "<name> to move" / "Match over". Names: "You" / "Your opponent".
- Use `MatchAuthority.prepareTurn` to build the outgoing payload; there is no remote write to confirm, so the turn commits as soon as the move resolves.
- **Opening a link for a match already on this device**: if the saved snapshot (S20) is a `ByLink` match with the same match id, accept the link only if its `moveNumber` is higher than the saved one; an older or equal link is refused with "That link is older than the match on this phone." A link for a different match replaces the snapshot.
- **Errors**: `LinkError` with the three `MatchLink` messages, the codec's version messages (worded for a player: "That match was sent from a newer version of Banana Chess." / "That match was sent from an older version of Banana Chess and can't be opened."), and the stale-link message. `HomeViewModel.linkError` holds the current one; `dismissLinkError()` clears it. S22 shows it.
- Link matches are saved by the snapshot store like any other match, so a correspondence game survives closing the app between turns.
- Undo is unavailable in link play. "Play again" is unavailable; "Done" returns Home.

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
- `link/LinkPlay.kt` (start, open, stale check) and `link/LinkError.kt`.
- `ByLink` behaviour in `MatchViewModel`: seat control, `linkToSend`, handoff title and share text, indicator text, animating an incoming `lastMove`.
- `HomeViewModel.invite()`, `open(url)`, `linkError`.
- Tests: a new invitation has a link, the title "Send the invitation" and the indicator "Send the link to start"; opening that link on a second view model seats the opener as player one with the same board; a full four-move exchange between two view models stays in agreement; an incoming move animates its events; each error case sets the right message; a stale link is refused and a newer one accepted; opening a golden link from `golden.json.gz` (copy two samples into `app/src/test/resources`) works.

## Out of Scope
- Intent filters, the share sheet and alerts (S22).
- `https://` links and App Links.

## Acceptance Criteria
- [ ] Two view models can play a complete link match against each other in a test, exchanging only URL strings.
- [ ] Every `LinkError` has a distinct, player-readable message.
- [ ] Tests added; build, lint and existing tests green.

## Verification
```bash
source Android/scripts/env.sh
cd Android && ./gradlew :app:testDebugUnitTest :app:lintDebug ktlintCheck :app:assembleDebug
```

## Estimate Rationale
3: several interacting rules (seats, stale links, animating an incoming move) but all testable with two view models passing strings.

## Actuals
- **Actual points:**
- **What took longer / shorter than expected:**
- **Surprises / unknowns that surfaced:**
- **Review fixes applied:**

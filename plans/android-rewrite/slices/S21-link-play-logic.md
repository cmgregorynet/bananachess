---
id: S21
title: Add play-by-link match logic
layer: ui
points: 3
depends_on: [S20]
touches: [Android/app/src/main/kotlin/com/charlesbarton/bananachess/link/, Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/MatchViewModel.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/MatchUiState.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/home/HomeViewModel.kt, Android/app/src/test/kotlin/com/charlesbarton/bananachess/link/, Android/app/src/test/kotlin/com/charlesbarton/bananachess/match/LinkPlayTest.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/MatchSource.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/BananaChessApp.kt]
status: done
branch: slice/android-rewrite/S21
started: 2026-11-23T20:49
completed: 2026-11-23T20:59
actual_points: 3
review_fixes: tests no longer run the timeline on construction; simpler incoming events
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
- **Established by S10 (read `Android/engine/src/main/kotlin/com/charlesbarton/bananachess/engine/online/`):** `GameStateCodec.encode/decode/toJson/fromJson` (throws `MatchCodecError.Malformed`); `MatchCodec`; `MatchLink.url(payload): String` and `MatchLink.payload(url: String)` (throws `MatchLinkError` or `MatchCodecError`); `MatchPayload(matchId, moveNumber, state, lastMove)` with `LastMove(action, playedBy, events)`; `MatchAuthority(payload, localPlayer)` is **immutable** — `prepareTurn(action)` returns `PreparedTurn(payload, events, expectedPredecessor)`, and `commit`/`adopt` return a new authority that you must keep. The engine's own error messages differ slightly from the wording in this slice; where this slice gives player-facing wording, map to it in `:app`. `MatchOptions` exists in both `engine/online/` and `:app` `match/MatchMode.kt`: in S21, delete the `:app` copy and use the engine's.
- **Established by S15 (read every file in `Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/` and `Android/app/src/test/kotlin/com/charlesbarton/bananachess/match/MatchTestSupport.kt` first):** `MatchViewModel(mode, initialState, blendEffects, haptics, reduceMotion, scope)` exposes `state: StateFlow<MatchUiState>` and `tap(position)`, `confirmSelfBlend()`, `cancelSelfBlend()`, `dismissError()`, `onSceneInactive()`, `onSceneActive()`, `retireBurst(id)`, `retireSplatter(id)`, `requestRestart()`, `cancelRestart()`, `restart()`, `undo()`, and internal `perform(action)`. `MatchUiState` carries everything a view needs as derived properties (`isRotated`, `seat(player)`, `scoreboard(player)`, `turnIndicatorText`, `resultText`, `resultDetail`, `piecesOnBoard`, `blenderHomes`, `purchasableSpawns`, `blendersAwaitingRespawn`, `deliveryEdge(position)`, `selectedSquare`, `legalDestinations`, `flash`, `blendBursts`, `splatter`, `pendingSelfBlend`, `errorMessage`, `canUndo`, `canPlayAgain`) and `squareDescription(position)`. Views draw; the view model decides. Extension points: CPU turns start at the end of the private `finishTimeline()`, in `restart()` and at construction, and reply through `perform(action)`; snapshots save at the same places; an incoming link move sets `displayState` to the position before it and calls the private `animate(events)`.
- **Established by S17 (read `match/MatchScreen.kt`, `ResultOverlay.kt`, `MatchDialogs.kt`, `BananaChessApp.kt`, and `test/.../match/MatchScreenTest.kt`):** `MatchScreen(viewModel, settings, onExit, onShowRules, modifier, showsDebugControls)`. The one production construction site of the view model is `newMatchViewModel(start, reduceMotion)` in `BananaChessApp.kt`; it is scoped to the Match back-stack entry, so changing `graph.matchStart.current` while a match is showing does not swap the match (pop and re-navigate instead). Commented plug-in spots exist in `MatchScreen` for the splatter (S18), the thinking indicator (S19) and the send button (S22). Once the match is over the result scrim swallows taps beneath it; anything that must stay usable then goes in `ResultOverlay`'s `notice` slot. `PaperAlertDialog(title, message, confirmLabel, onConfirm, onDismiss, dismissLabel = null)` is a one-button alert. `MatchTags.TURN_INDICATOR`, `ERROR_BANNER`, `RESULT`.
- **Keep Compose tests fast:** `composeRule.waitForIdle()` after every move of a whole match took over three minutes under load and timed out. Drive long sequences through the view model with `scope.settle()` and call `waitForIdle()` once at the end, as `MatchScreenTest`'s played-out test now does.
- **Changed by the architecture review:** matches reach the Match screen through `Route.Match(source: MatchSource)` (S26), not an in-memory holder. Add a `ByLink` source here carrying what is needed to rebuild the link match (the link URL or the decoded payload); the snapshot store (S20) still saves link matches between turns.
- **Established by S26 (read `AppContainer.kt`, `BananaChessApplication.kt`, `match/MatchSource.kt`, `BananaChessApp.kt`, and the process-death tests in `test/.../match/MatchScreenTest.kt` and `AppContainerTest.kt`):** app-wide objects live in `AppContainer(settings, dispatchers: AppDispatchers(main, default, io), ioScope)` built once per process by `AppContainer.create(context)`; tests build `AppContainer(InMemorySettingsStore(...))`. The Match route is `Route.Match(source: MatchSource)`; `MatchSource.New(kind, ruleSet, boardSetup, boardWrap, blenderBattles, seed)` deals deterministically; `MatchSource.Saved` is reserved and currently returns Home. `matchStartOf(source)` in `BananaChessApp.kt` resolves a source; `newMatchViewModel(container, start, reduceMotion)` is the single construction site. Process death is simulated in tests by `restartTheProcess()`.

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
- **Estimate vs actual:** 3 → 3. The engine API dropped straight in; persistence needed no store change because a link's moveNumber always equals history.size. Only snag: the timeline shows its first event at once, which tripped the "opens on the position before" tests.
- **What shipped:** `link/LinkPlay.kt`, `link/LinkError.kt` (NotAMatchLink, Damaged, FromNewerVersion, FromOlderVersion, Stale — distinct player-facing copy). `MatchSource.ByLink(link, localPlayer)` resolved in `MatchOpening` via `LinkPlay.start(source, snapshots.load())`, preferring the snapshot at the same match and move or later. `MatchUiState.linkToSend`, `linkHandoffTitle`, `linkShareText`; link-play indicator text. `HomeViewModel.invite()` (sync) / `open(url)` (async: `linkToOpen` or `linkError`, `dismissLinkError()`). The `:app` MatchOptions copy is gone. Golden iOS links (seed 1001, steps 0–1) open, and step 1 animates from step 0.
- **Differences from iOS:** Android sends `lastMove` and animates incoming moves when the saved snapshot is exactly the position before; otherwise it opens on the result. iOS ignores `lastMove`, so links stay compatible both ways. After process death `lastMove` is lost (same as iOS).
- **Review fixes:** test helpers no longer run the timeline on construction; simplified incoming-event derivation.
- **Outside touches:** `match/MatchMode.kt` (MatchOptions removed; MatchStart gained lastMove/animateFrom), `HomeViewModelTest.kt` import, `golden-links.json` test resource.
- **Verification:** merged tree, no cache — engine tests, app tests, lint, ktlint, assembleDebug.
- **Notes for S22:** switch `onInvite` to `Route.Match(homeViewModel.invite())`. Home's LaunchedEffect navigates `linkToOpen` only while Home is composed, so an intent arriving during a match must pop to Home or navigate itself. Consider hiding Restart in ByLink: it re-deals at history 0, so the opponent would refuse the result as stale.

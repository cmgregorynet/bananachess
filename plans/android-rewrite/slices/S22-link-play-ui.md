---
id: S22
title: Wire play-by-link into Android: deep links, sharing and error alerts
layer: ui
points: 2
depends_on: [S21]
touches: [Android/app/src/main/AndroidManifest.xml, Android/app/src/main/kotlin/com/charlesbarton/bananachess/MainActivity.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/BananaChessApp.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/home/HomeScreen.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/MatchScreen.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/link/LinkHandoff.kt, Android/app/src/test/kotlin/com/charlesbarton/bananachess/link/LinkUiTest.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/MatchSource.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/BananaChessApp.kt]
status: todo
branch:
started:
completed:
actual_points:
review_fixes:
blocked_reason:
---

# S22 — Wire play-by-link into Android: deep links, sharing and error alerts

## Goal
Tapping a Banana Chess link opens the match, and sending your move is one button.

## Context
- Logic is done (S21): `HomeViewModel.invite()`, `open(url)`, `linkError`; `MatchViewModel.linkToSend`, handoff title, share text.
- **Deep link**: an intent filter on `MainActivity` for `ACTION_VIEW`, categories `DEFAULT` and `BROWSABLE`, scheme `bananachess`, host `m`. The activity is `singleTop`; handle both the launch intent and `onNewIntent`, and consume each intent once (rotation or process restore must not re-open a link).
- **Home**: "Invite by Link" calls `invite()` and opens the match.
- **Match** (link mode): a capsule button under the bottom scoreboard with `testTag` `sendLink` and the handoff title, shown whenever `linkToSend` is not null. It launches the system share sheet (`ACTION_SEND`, `text/plain`, body = share text + a newline + the link) through `Intent.createChooser`.
- **Error surfaces**:
  - `linkError` is shown as an alert titled "Can't open that match" with an "OK" button. It is hosted at the **app level** in `BananaChessApp.kt`, so it appears over whatever screen is showing — Home with or without a "Resume match" entry, a match in progress, or the Rules screen — and the current screen is left untouched.
  - If no app can handle the share intent (`ActivityNotFoundException`), the Match screen shows its existing error banner: "Couldn't open the share sheet. The link was copied instead." and the link is copied to the clipboard.
- Links are long (about 700–2,700 characters); do not display the raw link in the UI.
- **Established by S10 (read `Android/engine/src/main/kotlin/com/charlesbarton/bananachess/engine/online/`):** `GameStateCodec.encode/decode/toJson/fromJson` (throws `MatchCodecError.Malformed`); `MatchCodec`; `MatchLink.url(payload): String` and `MatchLink.payload(url: String)` (throws `MatchLinkError` or `MatchCodecError`); `MatchPayload(matchId, moveNumber, state, lastMove)` with `LastMove(action, playedBy, events)`; `MatchAuthority(payload, localPlayer)` is **immutable** — `prepareTurn(action)` returns `PreparedTurn(payload, events, expectedPredecessor)`, and `commit`/`adopt` return a new authority that you must keep. The engine's own error messages differ slightly from the wording in this slice; where this slice gives player-facing wording, map to it in `:app`. `MatchOptions` exists in both `engine/online/` and `:app` `match/MatchMode.kt`: in S21, delete the `:app` copy and use the engine's.
- **Established by S15 (read every file in `Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/` and `Android/app/src/test/kotlin/com/charlesbarton/bananachess/match/MatchTestSupport.kt` first):** `MatchViewModel(mode, initialState, blendEffects, haptics, reduceMotion, scope)` exposes `state: StateFlow<MatchUiState>` and `tap(position)`, `confirmSelfBlend()`, `cancelSelfBlend()`, `dismissError()`, `onSceneInactive()`, `onSceneActive()`, `retireBurst(id)`, `retireSplatter(id)`, `requestRestart()`, `cancelRestart()`, `restart()`, `undo()`, and internal `perform(action)`. `MatchUiState` carries everything a view needs as derived properties (`isRotated`, `seat(player)`, `scoreboard(player)`, `turnIndicatorText`, `resultText`, `resultDetail`, `piecesOnBoard`, `blenderHomes`, `purchasableSpawns`, `blendersAwaitingRespawn`, `deliveryEdge(position)`, `selectedSquare`, `legalDestinations`, `flash`, `blendBursts`, `splatter`, `pendingSelfBlend`, `errorMessage`, `canUndo`, `canPlayAgain`) and `squareDescription(position)`. Views draw; the view model decides. Extension points: CPU turns start at the end of the private `finishTimeline()`, in `restart()` and at construction, and reply through `perform(action)`; snapshots save at the same places; an incoming link move sets `displayState` to the position before it and calls the private `animate(events)`.
- **Established by S17 (read `match/MatchScreen.kt`, `ResultOverlay.kt`, `MatchDialogs.kt`, `BananaChessApp.kt`, and `test/.../match/MatchScreenTest.kt`):** `MatchScreen(viewModel, settings, onExit, onShowRules, modifier, showsDebugControls)`. The one production construction site of the view model is `newMatchViewModel(start, reduceMotion)` in `BananaChessApp.kt`; it is scoped to the Match back-stack entry, so changing `graph.matchStart.current` while a match is showing does not swap the match (pop and re-navigate instead). Commented plug-in spots exist in `MatchScreen` for the splatter (S18), the thinking indicator (S19) and the send button (S22). Once the match is over the result scrim swallows taps beneath it; anything that must stay usable then goes in `ResultOverlay`'s `notice` slot. `PaperAlertDialog(title, message, confirmLabel, onConfirm, onDismiss, dismissLabel = null)` is a one-button alert. `MatchTags.TURN_INDICATOR`, `ERROR_BANNER`, `RESULT`.
- **Keep Compose tests fast:** `composeRule.waitForIdle()` after every move of a whole match took over three minutes under load and timed out. Drive long sequences through the view model with `scope.settle()` and call `waitForIdle()` once at the end, as `MatchScreenTest`'s played-out test now does.
- **Changed by the architecture review:** a deep link becomes a navigation to `Route.Match(MatchSource.ByLink(…))` (S21). Dedupe intents through the activity's saved state or the navigation back stack (mark the intent consumed in `onSaveInstanceState`-backed state), not through any in-memory holder, so rotation and process restore never re-open a link. Wherever a link match replaces the match on screen, pop and re-navigate: the view model is scoped to the back-stack entry.
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
- Manifest intent filter and `singleTop`; intent handling in `MainActivity`.
- App-level link error alert; Home wiring; the send button, share launch and clipboard fallback.
- Robolectric tests: launching the activity with a valid `bananachess://m/1/…` intent lands on the Match screen with the opener to move; a damaged link shows "Can't open that match" over Home; a link arriving while a different match is on screen shows the alert without disturbing that match when the link is bad, and switches match when it is good; "Invite by Link" shows `sendLink` labelled "Send the invitation" and the indicator "Send the link to start"; pressing it fires an `ACTION_SEND` chooser whose text contains the link; with no handler, the banner appears and the clipboard holds the link.

## Out of Scope
- `https://` App Links and `assetlinks.json` (no domain exists yet).
- Any server.

## Acceptance Criteria
- [ ] `adb`-free proof: a Robolectric test resolves the `bananachess://m/1/x` view intent to `MainActivity`.
- [ ] The link error alert is asserted in three states: Home empty, Home with a resume entry, and during a match.
- [ ] The same intent delivered twice opens the match once.
- [ ] Tests added; build, lint and existing tests green.

## Verification
```bash
source Android/scripts/env.sh
cd Android && ./gradlew :app:testDebugUnitTest :app:lintDebug ktlintCheck :app:assembleDebug
cd .. && /usr/bin/grep -n 'android:scheme="bananachess"' Android/app/src/main/AndroidManifest.xml
```

## Estimate Rationale
2: manifest, intent handling and three error surfaces.

## Actuals
- **Actual points:**
- **What took longer / shorter than expected:**
- **Surprises / unknowns that surfaced:**
- **Review fixes applied:**

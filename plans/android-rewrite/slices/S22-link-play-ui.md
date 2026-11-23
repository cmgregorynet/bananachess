---
id: S22
title: Wire play-by-link into Android: deep links, sharing and error alerts
layer: ui
points: 2
depends_on: [S21]
touches: [Android/app/src/main/AndroidManifest.xml, Android/app/src/main/kotlin/com/charlesbarton/bananachess/MainActivity.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/BananaChessApp.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/home/HomeScreen.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/match/MatchScreen.kt, Android/app/src/main/kotlin/com/charlesbarton/bananachess/link/LinkHandoff.kt, Android/app/src/test/kotlin/com/charlesbarton/bananachess/link/LinkUiTest.kt]
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

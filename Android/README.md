# Banana Chess for Android

A native Kotlin and Jetpack Compose app that plays exactly the same game as the iPhone app: rules version 18, the four computer levels, the paper-and-watercolour look, and play by link that works between an Android phone and an iPhone. The Swift engine in `Engine/` is the specification; `:engine` is its Kotlin port, held to it by fixtures the Swift engine writes.

What it has: local two-player, matches against the computer (Easy, Medium, Hard, Brutal), every board setup, edge setting, wildcard rule and blender battles, the rules screen and the first-match teaching overlay, resume after the app is closed, and play by `bananachess://` link. What it does not have: live online play (no Game Center equivalent), `https://` App Links, sound, a dark theme, or tablet and landscape layouts. The app is portrait only and light theme only, and runs on Android 8.0 (API 26) and later.

## Install the toolchain (once)

The build needs JDK 17 and the Android SDK, both from Homebrew. None of these needs `sudo`.

```bash
brew install openjdk@17
brew install --cask android-commandlinetools

source Android/scripts/env.sh
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
yes | "$SDKMANAGER" --licenses
"$SDKMANAGER" "platform-tools" "platforms;android-37.0" "platforms;android-37.2" "build-tools;37.0.0"
```

`Android/scripts/env.sh` exports `JAVA_HOME` (Homebrew's `openjdk@17`) and `ANDROID_HOME` (`$(brew --prefix)/share/android-commandlinetools`) for the current shell. It does not touch shell profiles, so source it in every new shell before running Gradle. To see what is installed:

```bash
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --list_installed
```

Those four packages are all the command line needs. The emulator is optional; see [Run it](#run-it).

### Android Studio

Android Studio also works. Open the `Android/` folder (not the repository root). Two settings matter:

- **Gradle JDK** (Settings → Build, Execution, Deployment → Build Tools → Gradle) should be JDK 17. Point it at `$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home`, the same JDK `env.sh` uses. The build compiles with a JDK 17 toolchain either way, and Robolectric is held to API 35 because of it.
- **SDK location** should be the Homebrew SDK, `$(brew --prefix)/share/android-commandlinetools`, so Studio and the command line share one set of packages. Studio records it in `Android/local.properties`, which is gitignored.

Studio writes files that stay out of git: `.idea/` and `local.properties` are gitignored. It may also offer to write `gradle/gradle-daemon-jvm.properties`, which asks Gradle for a newer daemon JDK (25). Do not commit it: the build, CI and Robolectric are pinned to JDK 17.

## Build, test and lint

```bash
source Android/scripts/env.sh
cd Android
./gradlew :engine:test :app:testDebugUnitTest :app:lintDebug ktlintCheck :app:assembleDebug
```

This is exactly what CI runs (`.github/workflows/android.yml`, on pull requests and pushes to `main` that touch `Android/` or `Web/fixtures/`).

| Command | What it does |
|---|---|
| `./gradlew :engine:test` | Engine tests (JUnit Jupiter, `kotlin.test`): 441 tests, including all 108 conformance games |
| `./gradlew :app:testDebugUnitTest` | App tests on the JVM under Robolectric, including Compose UI tests: 329 tests |
| `./gradlew :app:testReleaseUnitTest` | The same 329 app tests against the release variant |
| `./gradlew :app:lintDebug` | Android lint; warnings are errors |
| `./gradlew :app:lintRelease` | Lint for the release variant |
| `./gradlew ktlintCheck` | Formatting check for both modules and the build scripts |
| `./gradlew ktlintFormat` | Fix formatting |
| `./gradlew :app:assembleDebug` | Debug APK in `app/build/outputs/apk/debug/` |

The build cache is on. To be sure every test really ran rather than being restored from the cache, add `--no-build-cache --rerun-tasks`.

## Run it

### On a phone

Turn on USB debugging on the phone (Settings → About phone → tap Build number seven times, then Developer options → USB debugging), connect it, and accept the prompt on the phone. Then, from the repository root:

```bash
source Android/scripts/env.sh
(cd Android && ./gradlew :app:assembleDebug)
"$ANDROID_HOME/platform-tools/adb" devices
"$ANDROID_HOME/platform-tools/adb" install -r Android/app/build/outputs/apk/debug/app-debug.apk
```

`-r` replaces an installed copy and keeps its data, including a match in progress. The debug build shows the Undo button and turn counter; the release build does not.

### On the emulator

The emulator and an image are optional extras on top of the four packages above. This Mac has `emulator` and `system-images;android-35;google_apis;arm64-v8a` installed in the Homebrew SDK and an AVD named `BananaPixel` built from that image. To set one up elsewhere:

```bash
source Android/scripts/env.sh
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "emulator" "system-images;android-35;google_apis;arm64-v8a"
"$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd -n BananaPixel -k "system-images;android-35;google_apis;arm64-v8a" -d pixel_8
"$ANDROID_HOME/emulator/emulator" -avd BananaPixel
```

With the emulator running, `adb install` works exactly as for a phone. In Android Studio, pick the AVD in the device menu and press Run.

## Release

```bash
Android/scripts/release.sh
```

It sources `env.sh` itself, builds the release APK and App Bundle (`:app:assembleRelease :app:bundleRelease`), and prints where each is and whether it is signed:

```
Release outputs:
  APK     …/Android/app/build/outputs/apk/release/app-release-unsigned.apk  (UNSIGNED)
  Bundle  …/Android/app/build/outputs/bundle/release/app-release.aab  (UNSIGNED)
  Mapping …/Android/app/build/outputs/mapping/release/mapping.txt (keep it to read crash reports)
```

- **Version** is 0.1 (version code 1), the same as iOS.
- **Signing** comes from four values: `BANANA_KEYSTORE` (the keystore's path), `BANANA_KEYSTORE_PASSWORD`, `BANANA_KEY_ALIAS` and `BANANA_KEY_PASSWORD`. Set them as environment variables or as Gradle properties in `~/.gradle/gradle.properties`, never in the repository (`*.keystore` is gitignored). With any of them missing the build is unsigned, which is fine for checking it and useless for the Play Store.
- **R8** shrinks code and resources. `app/proguard-rules.pro` holds no rules on purpose: kotlinx.serialization ships its own, which cover the navigation routes, `MatchSource`, `MatchMode` and the saved snapshot, and the engine's codecs build their JSON by hand with no reflection. After changing anything serialized, check what R8 kept in `app/build/outputs/mapping/release/`.
- **Keep `mapping.txt`** from every build you distribute. Without it a release crash report is unreadable.
- **Release unit tests.** `gradle.properties` sets `android.onlyEnableUnitTestForTheTestedBuildType=false`, which AGP 9 needs before it will create `:app:testReleaseUnitTest`. Robolectric reads the release manifest, which must not carry the test-only `ComponentActivity` entry, so `app/src/testRelease/…/TestBananaChessApplication.kt` registers it for the tests (and `BananaChessApplication` is `open` so it can).

## Layout

```
Android/
  build.gradle.kts, settings.gradle.kts, gradle.properties
  gradle/libs.versions.toml    every version, and nothing pinned anywhere else
  scripts/env.sh               JAVA_HOME and ANDROID_HOME for the current shell
  scripts/release.sh           release APK and bundle, with signing status
  engine/                      :engine — pure Kotlin/JVM, com.charlesbarton.bananachess.engine
    src/main/kotlin/…/engine/
      model/       Board, Piece, GameState, GameAction, GameEvent, wildcards
      setup/       the opening arrangements
      random/      SplitMix64, reproducing Swift's generator and shuffle
      rules/       RulesEngine, the single transition path, and event replay
      cpu/         Evaluation, the search, the four difficulty levels
      online/      MatchPayload, the Swift-compatible JSON codec, MatchLink, MatchAuthority
    src/test/resources/golden.json.gz   written by the Swift engine
  app/                         :app — the Compose app, com.charlesbarton.bananachess
    src/main/kotlin/…/
      AppContainer.kt          the one container: settings, snapshots, CPU, dispatchers
      BananaChessApplication.kt, MainActivity.kt, BananaChessApp.kt (routes, navigation, link alert)
      art/        palette, paper grain, watercolour wash, piece shapes, blend effects
      home/       Home, its view model, the computer options sheet
      match/      the Match screen, board, view model, event timeline, haptics, resume snapshots
      link/       play by link: opening links, their errors, handing one off
      rules/      the rules screen and the first-match teaching overlay
      settings/   the DataStore-backed settings
    compose-stability.conf     engine types Compose may treat as stable
```

- `:engine` never imports `android.*` or `androidx.*`. It is the whole game: everything that changes a match goes through `RulesEngine.apply`, and the app only replays the events it returns.
- `:app` depends on `:engine`. View models own the state and expose it as `StateFlow`; dependencies are passed in through constructors with production defaults, and `AppContainer`, owned by the `Application`, builds the real ones.
- The Match route carries a `MatchSource` (new, saved, or by link) rather than a live object, so the system can recreate the screen after it kills the process.

## How the engine is verified

The Swift engine is the specification, and the Kotlin port is held to two fixtures it writes. Neither is hand-edited.

**The conformance fixture**, `Web/fixtures/conformance.json.gz`, is shared with the web port. It holds 108 complete games (13,829 moves): both rule sets × three board setups × three edge settings × battles on and off × three scripted drivers. For every move the Kotlin replay checks the legal-move list (for each game's first eight moves), every event, the board after, whose turn it is, both scores, the turn number and the outcome. Engine tests receive its absolute path as the system property `conformance.fixture`.

**The golden fixture**, `engine/src/test/resources/golden.json.gz`, pins what the conformance fixture cannot see: 146 positions sampled from 18 games, each with the exact payload JSON bytes and `bananachess://` link the iPhone writes, the two 64-bit position hashes (`boardKey`, which drives the repetition rule, and `positionKey`, which seeds battle dice), and the CPU evaluation for both players. The Kotlin codec re-encodes every payload byte for byte and decodes every link, which is what makes links open on both platforms. It is written by `Engine/Sources/GoldenFixtures`.

Change a rule in Swift, regenerate, and the Kotlin tests fail until the port agrees. From the repository root:

```bash
export DEVELOPER_DIR=/Applications/Xcode-26.5.0.app/Contents/Developer

# Conformance fixture (shared with Web/)
(cd Web && npm run fixtures)

# Golden fixture
(cd Engine && swift run GoldenFixtures /tmp/golden.json)
gzip -n -9 -c /tmp/golden.json > Android/engine/src/test/resources/golden.json.gz
```

Both are deterministic: run against an unchanged engine, they reproduce the committed data exactly. The golden file comes out byte for byte, because `gzip -n` leaves the name and time out of its header. `npm run fixtures` uses plain `gzip -9 -f`, so git reports `conformance.json.gz` as changed after every run even when the games are identical; compare with `gzip -dc` before committing it. The Swift engine's own suite is `cd Engine && swift test` (171 tests). A handful of rarer cases (a non-null `respawnTurn`, draws, wildcard wins and some string escapes in payloads) were captured from Swift once and are kept as string constants in `engine/src/test/…/online/SwiftSamples.kt`.

The engine suite also carries the Swift engine's own hand-written tests: the rules tests under `rulesported/` (all but three that duplicated existing Kotlin tests), and the CPU and codec tests beside the code they cover.

## Things to know

- Every version is pinned in `gradle/libs.versions.toml`: Gradle 9.9.2, AGP 9.5.2 (built-in Kotlin, so `:app` applies no `kotlin-android` plugin), Kotlin 2.4.21, compileSdk 37.2, targetSdk 37, minSdk 26, Compose BOM 2026.11.01. Module build files hold no version literals. Check the comments at the top of the catalog before bumping anything: these versions constrain each other.
- Robolectric simulates API 35 (`app/src/test/resources/robolectric.properties`). It needs Java 21 for API 36 and later, and this project builds on JDK 17. The app itself compiles against and targets API 37.
- Compose UI tests use the v2 rule, `androidx.compose.ui.test.junit4.v2`.
- To test anything that goes through `MainActivity`, drive it with `Robolectric.buildActivity(MainActivity::class.java, intent).setup()` inside `AndroidComposeTestRule(activityRule = …)`, as `link/LinkUiTest.kt` does. Calling `ActivityScenario.launch` in a test body deadlocks on close under the Compose rule.
- ktlint uses the `ktlint_official` style. Run `./gradlew ktlintFormat` before `ktlintCheck`.
- Debug-only controls (Undo, the turn counter) are shown when the app is debuggable, read at runtime; `BuildConfig` is not enabled.
- The CPU's search is bounded by wall-clock time (Hard thinks for up to two seconds, Brutal four), so on a slow phone it stays responsive but sees less deep and plays weaker. That is as on iOS.

## Known open items

- **A possible test flake.** `ScaffoldTest.skipTheIntroduction` hung once (for over half an hour) while the release unit tests were being set up, probably in DataStore under Robolectric. It did not reproduce in five reruns. If the app tests ever stall, suspect that test first.
- **The release build has been checked only on an emulator, and only in part.** The shrunk APK, signed with the local debug key, opened an iOS-written link and offered to resume it after a force-stop, with no crash. A computer match has not yet been played on a release build, and no release build has run on a physical phone.
- **The look has only been judged on the JVM.** Appearance is checked by pixel tests under Robolectric, not side by side with the iPhone.

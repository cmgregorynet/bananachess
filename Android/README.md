# Banana Chess for Android

A native Kotlin and Jetpack Compose app. The Swift engine in `Engine/` is the specification; `:engine` is its Kotlin port.

- `:engine` is pure Kotlin/JVM (`com.charlesbarton.bananachess.engine`). It never imports `android.*` or `androidx.*`.
- `:app` is the Compose app (`com.charlesbarton.bananachess`). It depends on `:engine`.

## Install the toolchain (once)

Command-line tools only: no Android Studio, no emulator. None of these needs `sudo`.

```bash
brew install openjdk@17
brew install --cask android-commandlinetools

source Android/scripts/env.sh
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
yes | "$SDKMANAGER" --licenses
"$SDKMANAGER" "platform-tools" "platforms;android-37.2" "build-tools;37.0.0"
```

`Android/scripts/env.sh` exports `JAVA_HOME` and `ANDROID_HOME` for the current shell. It does not touch shell profiles, so source it in every new shell before running Gradle.

## Build, test and lint

```bash
source Android/scripts/env.sh
cd Android
./gradlew :engine:test :app:testDebugUnitTest :app:lintDebug ktlintCheck :app:assembleDebug
```

| Command | What it does |
|---|---|
| `./gradlew :engine:test` | Engine tests (JUnit Jupiter, `kotlin.test`) |
| `./gradlew :app:testDebugUnitTest` | App tests on the JVM under Robolectric, including Compose UI tests |
| `./gradlew :app:lintDebug` | Android lint; warnings are errors |
| `./gradlew ktlintCheck` | Formatting check for both modules and the build scripts |
| `./gradlew ktlintFormat` | Fix formatting |
| `./gradlew :app:assembleDebug` | Debug APK in `app/build/outputs/apk/debug/` |

## Things to know

- Every version is pinned in `gradle/libs.versions.toml`. Module build files hold no version literals.
- Engine tests receive the system property `conformance.fixture`: the absolute path of `Web/fixtures/conformance.json.gz`.
- Robolectric simulates API 35 (`app/src/test/resources/robolectric.properties`). It needs Java 21 for API 36 and later, and this project builds on JDK 17. The app itself compiles against and targets API 37.
- The app is portrait only and light theme only.

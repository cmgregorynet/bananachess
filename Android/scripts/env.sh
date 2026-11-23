# Source this before any Gradle command:  source Android/scripts/env.sh
# Exports JAVA_HOME (Homebrew openjdk@17) and ANDROID_HOME (Homebrew
# android-commandlinetools SDK root). Nothing is written to shell profiles.

_bc_env_fail() {
  echo "Android/scripts/env.sh: $1" >&2
  echo "See Android/README.md for the install commands." >&2
}

if ! command -v brew >/dev/null 2>&1; then
  _bc_env_fail "Homebrew (brew) is not on PATH."
  unset -f _bc_env_fail
  return 1 2>/dev/null || exit 1
fi

_bc_jdk_prefix="$(brew --prefix openjdk@17 2>/dev/null)"
_bc_java_home="$_bc_jdk_prefix/libexec/openjdk.jdk/Contents/Home"
_bc_android_home="$(brew --prefix)/share/android-commandlinetools"

if [ -z "$_bc_jdk_prefix" ] || [ ! -x "$_bc_java_home/bin/java" ]; then
  _bc_env_fail "JDK 17 not found. Run: brew install openjdk@17"
  unset _bc_jdk_prefix _bc_java_home _bc_android_home
  unset -f _bc_env_fail
  return 1 2>/dev/null || exit 1
fi

if [ ! -x "$_bc_android_home/cmdline-tools/latest/bin/sdkmanager" ]; then
  _bc_env_fail "Android SDK not found at $_bc_android_home. Run: brew install --cask android-commandlinetools"
  unset _bc_jdk_prefix _bc_java_home _bc_android_home
  unset -f _bc_env_fail
  return 1 2>/dev/null || exit 1
fi

export JAVA_HOME="$_bc_java_home"
export ANDROID_HOME="$_bc_android_home"
case ":$PATH:" in
  *":$JAVA_HOME/bin:"*) ;;
  *) export PATH="$JAVA_HOME/bin:$PATH" ;;
esac

unset _bc_jdk_prefix _bc_java_home _bc_android_home
unset -f _bc_env_fail

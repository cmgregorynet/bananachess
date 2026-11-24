#!/usr/bin/env bash
# Builds the release APK and App Bundle, then says where they are and whether they are signed.
#
#   Android/scripts/release.sh
#
# Signing is optional. Set all four of BANANA_KEYSTORE, BANANA_KEYSTORE_PASSWORD,
# BANANA_KEY_ALIAS and BANANA_KEY_PASSWORD (as environment variables, or as Gradle properties in
# ~/.gradle/gradle.properties) to sign; with any of them missing the outputs are unsigned.
set -euo pipefail

android_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=env.sh
source "$android_dir/scripts/env.sh"
cd "$android_dir"

apk_dir="$android_dir/app/build/outputs/apk/release"
bundle_dir="$android_dir/app/build/outputs/bundle/release"
# A signed build names its APK differently from an unsigned one; clear both so only this build's
# outputs are reported.
rm -rf "$apk_dir" "$bundle_dir"

./gradlew :app:assembleRelease :app:bundleRelease

build_tools="$(sed -n 's/^buildTools = "\(.*\)"$/\1/p' gradle/libs.versions.toml)"
apksigner="$ANDROID_HOME/build-tools/$build_tools/apksigner"

echo
echo "Release outputs:"
for apk in "$apk_dir"/*.apk; do
  [ -e "$apk" ] || continue
  if "$apksigner" verify "$apk" >/dev/null 2>&1; then status="signed"; else status="UNSIGNED"; fi
  echo "  APK     $apk  ($status)"
done
for bundle in "$bundle_dir"/*.aab; do
  [ -e "$bundle" ] || continue
  # A bundle is signed with a JAR signature, so the signature block lives in META-INF.
  # No grep -q: under pipefail it would stop reading early and fail unzip with SIGPIPE.
  if unzip -Z1 "$bundle" | grep -E '^META-INF/[^/]+\.(RSA|DSA|EC)$' >/dev/null; then status="signed"; else status="UNSIGNED"; fi
  echo "  Bundle  $bundle  ($status)"
done
echo "  Mapping $android_dir/app/build/outputs/mapping/release/mapping.txt (keep it to read crash reports)"

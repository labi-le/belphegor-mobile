#!/usr/bin/env bash
# Full APK build, meant to run INSIDE `nix-shell shell.nix`.
# Logs stage markers so a tail of the log shows exactly where it is.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

echo "[toolchain] $(date -Is)"
go version
gomobile version 2>&1 | head -1 || true
java -version 2>&1 | head -1
gradle --version 2>&1 | grep -iE '^(Gradle|JVM|Kotlin)' || true
# This script builds with the nix gradle while CI and the README use ./gradlew.
# The two silently drifted apart once (wrapper 8.9 vs nix 8.14.4), which hid the
# fact that AGP could not be upgraded, so make a split fail here instead.
wrapper_gradle=$(sed -n 's|.*/gradle-\(.*\)-bin\.zip|\1|p' gradle/wrapper/gradle-wrapper.properties)
nix_gradle=$(gradle --version 2>/dev/null | sed -n 's/^Gradle \(.*\)$/\1/p')
if [ "$wrapper_gradle" != "$nix_gradle" ]; then
    echo "GRADLE_MISMATCH: wrapper $wrapper_gradle, nix $nix_gradle -- align gradle-wrapper.properties with nixpkgs (AGENTS.md)"
    exit 2
fi
echo "GRADLE_OK ($nix_gradle)"
echo "ANDROID_HOME=$ANDROID_HOME"
echo "ANDROID_NDK_ROOT=$ANDROID_NDK_ROOT"
if [ -d "$ANDROID_NDK_ROOT" ]; then echo "NDK_OK"; else echo "NDK_MISSING"; printf '  %s\n' "$ANDROID_HOME"/ndk/* 2>/dev/null || true; exit 2; fi

echo "[aar] $(date -Is)"
bash scripts/build-aar.sh

echo "[apk] $(date -Is)"
gradle --no-daemon --console=plain :app:assembleDebug

echo "[done] $(date -Is)"
printf 'APK: %s\n' app/build/outputs/apk/debug/*.apk

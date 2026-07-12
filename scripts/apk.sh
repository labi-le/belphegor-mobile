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
echo "ANDROID_HOME=$ANDROID_HOME"
echo "ANDROID_NDK_ROOT=$ANDROID_NDK_ROOT"
if [ -d "$ANDROID_NDK_ROOT" ]; then echo "NDK_OK"; else echo "NDK_MISSING"; printf '  %s\n' "$ANDROID_HOME"/ndk/* 2>/dev/null || true; exit 2; fi

echo "[aar] $(date -Is)"
bash scripts/build-aar.sh

echo "[apk] $(date -Is)"
gradle --no-daemon --console=plain :app:assembleDebug

echo "[done] $(date -Is)"
printf 'APK: %s\n' app/build/outputs/apk/debug/*.apk

#!/usr/bin/env bash
# Build belphegor.aar from the belphegor Go core via gomobile.
#
# The gomobile facade (package github.com/labi-le/belphegor/mobile) and the
# android clipboard backend must live INSIDE the belphegor module (they import
# belphegor/internal/*, which Go forbids other modules from importing). The core
# is vendored as a submodule at ./belphegor; the glue lives in ./glue and is
# overlaid onto the submodule by scripts/setup-core.sh — so nothing mobile is
# committed to the upstream core. go.work wires ./belphegor into the workspace.
#
# The transport stays QUIC (quic-go) — the same reference stack the desktop
# runs — so the phone is wire-compatible with desktop peers by construction.
#
# Requirements (see shell.nix): Go, Android SDK + NDK, gomobile (+init).
# Usage:  scripts/build-aar.sh
# Env:    ANDROID_API=26  TARGETS=android/arm64,android/arm,android/amd64
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_AAR="$HERE/app/libs/belphegor.aar"
ANDROID_API="${ANDROID_API:-26}"
TARGETS="${TARGETS:-android/arm64,android/arm,android/amd64}"
JAVAPKG="${JAVAPKG:-belphegor}"
PKG="github.com/labi-le/belphegor/mobile"

fail() { echo "error: $*" >&2; exit 1; }

command -v go >/dev/null || fail "go not on PATH (run: nix-shell)"
command -v gomobile >/dev/null || fail "gomobile not found (nix-shell runs 'gomobile init' for you)"
[ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ] || fail "ANDROID_HOME/ANDROID_SDK_ROOT not set (use shell.nix)"

# Overlay the mobile glue onto the core submodule (idempotent).
bash "$HERE/scripts/setup-core.sh"

cd "$HERE" # go.work here resolves ./belphegor
mkdir -p "$(dirname "$OUT_AAR")"

echo ">> preflight: $PKG cross-compiles for android/arm64 (via go.work)"
CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build "$PKG"

echo ">> gomobile bind ($TARGETS, api $ANDROID_API, javapkg $JAVAPKG)"
gomobile bind \
    -target="$TARGETS" \
    -androidapi "$ANDROID_API" \
    -javapkg "$JAVAPKG" \
    -o "$OUT_AAR" \
    "$PKG"

echo ">> wrote $OUT_AAR"
ls -lh "$OUT_AAR"
echo ">> generated Java package: ${JAVAPKG}.mobile  (classes: Mobile, Config, Node, Handler)"

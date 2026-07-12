#!/usr/bin/env bash
# Overlay the mobile glue onto the belphegor core submodule.
#
# The gomobile facade (github.com/labi-le/belphegor/mobile), the android
# clipboard backend (pkg/clipboard/android) and a node.Peers() helper must live
# INSIDE the belphegor module to import its internal/* packages. But none of it
# belongs in the upstream core repo, so it is stored here in ./glue and copied
# into the ./belphegor submodule at build time. The core stays pristine (the
# overlaid files are untracked in the submodule and never committed).
#
# Also pins golang.org/x/mobile to the revision the (nixpkgs) gomobile tool was
# built against; a gomobile/x-mobile version skew otherwise crashes the
# generated binding at init.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE="$HERE/belphegor"
XMOBILE="golang.org/x/mobile@v0.0.0-20241213221354-a87c1cf6cf46"

[ -f "$CORE/go.mod" ] || {
	echo "core submodule missing; run: git submodule update --init" >&2
	exit 1
}

echo ">> overlay ./glue -> ./belphegor"
cp -a "$HERE/glue/." "$CORE/"

echo ">> pin $XMOBILE + tidy"
(cd "$CORE" && go get "$XMOBILE" && go mod tidy)

echo ">> go work sync"
(cd "$HERE" && go work sync)

echo ">> setup-core done"

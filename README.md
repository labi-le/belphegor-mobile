# belphegor-mobile

Android client for [**belphegor**](https://github.com/labi-le/belphegor) — share the
clipboard between your Android phone and your computers over the local network.

The phone runs the same belphegor core as the desktop app, compiled to a native
library with [gomobile](https://pkg.go.dev/golang.org/x/mobile), so it is a
first-class peer: copy on your laptop, paste on your phone, and back. No cloud,
no account — everything stays on your LAN.

## Features

- **P2P over the LAN** — direct QUIC/TLS 1.3 connections to your desktop peers; nothing leaves your network.
- **Encrypted** — a shared secret gates the mesh (must match the desktop `--secret`).
- **Auto-discovery** — finds belphegor peers on the same Wi-Fi automatically, or add them by `ip:port`.
- **Text & images** — clipboard text and images sync both ways.
- **Foreground service** — keeps syncing while the app is open, and can restart on boot.
- **In-app updates** — checks GitHub Releases and offers a one-tap download & install.
- **Wire-compatible** with desktop belphegor by construction (same core).

## Requirements

- Android 8.0 (API 26) or newer.
- A computer running [belphegor](https://github.com/labi-le/belphegor) on the **same Wi-Fi / LAN**.
- Background sync (while the app is not in focus) additionally needs the LSPosed module — see [Background sync](#background-sync-lsposed).

## Install

Download the latest APK from the [Releases](https://github.com/labi-le/belphegor-mobile/releases) page:

| APK | For |
|-----|-----|
| `belphegor.apk` | real phones (arm64 / armv7) |
| `belphegor-x86_64.apk` | emulators / Waydroid (x86_64) |
| `belphegor-background-clipboard.apk` | the optional LSPosed module (see below) |

Enable *install unknown apps* for your browser or file manager, then open the APK.

> Releases built without a signing key ship as `*-unsigned.apk` and must be signed before they can be installed.

## Setup

1. Open the app and go to **Settings**.
2. Set a **Shared secret** matching your desktop `belphegor --secret`. Leave it empty for an open LAN, where anyone on the network can connect.
3. Optionally set a **Device name**, listen port, transport (QUIC by default, or TCP), and turn on **LAN auto-discovery**.
4. Go back and tap **Start sync**.

On the same network the app discovers your desktop peers automatically; you can
also add one manually as `ip:port` from the **Nodes** screen. Copy something on
any device and it shows up on the others.

## Background sync (LSPosed)

Android 10+ blocks apps from reading or writing the clipboard while they are
**not** in the foreground. Without a workaround, belphegor-mobile only syncs
while its window is open.

The optional **Belphegor Background Clipboard** LSPosed module lifts that
restriction **for belphegor only**: it hooks the framework clipboard guard
(`ClipboardService.clipboardAccessAllowed`) inside `system_server` and
force-allows calls made on the app's behalf, so sync keeps working in the
background. It targets belphegor's package and nothing else.

**Requirements:** a rooted device (Magisk / KernelSU) with LSPosed (Zygisk),
Android 10+.

**Install:**

1. Install the module — either from the app (**Settings → BACKGROUND → Install background module**) or the `belphegor-background-clipboard.apk` from Releases.
2. Open **LSPosed Manager → Modules**, enable **Belphegor Background Clipboard**, and set its **scope** to **System Framework** (the `android` / `system_server` process).
3. Reboot (a soft reboot is enough).

Without root + LSPosed the module does nothing and is not required — foreground
sync still works.

## Build from source

Needs the Android SDK + NDK, Go, and
[gomobile](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile). A reproducible
toolchain is provided via `shell.nix`:

```sh
git submodule update --init          # fetch the belphegor core (./belphegor)
nix-shell                            # Go + Android SDK/NDK + gomobile
scripts/build-aar.sh                 # build the native core -> app/libs/belphegor.aar
./gradlew :app:assembleDebug :background-clipboard:assembleDebug
```

The belphegor core is vendored as a git submodule under `./belphegor`; the
Android-specific glue (the gomobile facade, LAN discovery and the node helpers)
lives in `./glue` and is overlaid onto it at build time by
`scripts/build-aar.sh`, so the core stays a clean cross-platform project.

## License

belphegor-mobile is distributed under the same license as
[belphegor](https://github.com/labi-le/belphegor).

# Dev shell for building the Android app + the belphegor.aar (gomobile).
# The Android SDK is unfree and license-gated, so nixpkgs is imported with the
# required config here (overridable by passing your own `pkgs`).
{ pkgs ? import <nixpkgs> {
    config.allowUnfree = true;
    config.android_sdk.accept_license = true;
  }
}:

let
  # Versions may need bumping to match what your nixpkgs channel pins.
  androidComposition = pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ "35" ];
    buildToolsVersions = [ "35.0.0" ];
    includeNDK = true;
    ndkVersions = [ "26.1.10909125" ];
  };
  androidSdk = androidComposition.androidsdk;
  sdkRoot = "${androidSdk}/libexec/android-sdk";
in
pkgs.mkShell {
  packages = with pkgs; [
    go
    gomobile
    jdk17
    gradle
    androidSdk
  ];

  ANDROID_HOME = sdkRoot;
  ANDROID_SDK_ROOT = sdkRoot;
  ANDROID_NDK_ROOT = "${sdkRoot}/ndk/26.1.10909125";
  ANDROID_NDK_HOME = "${sdkRoot}/ndk/26.1.10909125";
  JAVA_HOME = "${pkgs.jdk17}";

  shellHook = ''
    unset GOROOT
    export GOTOOLCHAIN=local
    # The nixpkgs gomobile wrapper APPENDS its own store path to $GOPATH; if
    # GOPATH is empty that read-only store path becomes the sole entry and the
    # Go module cache lands there. Pin a writable GOPATH first.
    export GOPATH="''${GOPATH:-$HOME/go}"
    export PATH="$PATH:$GOPATH/bin"

    # cache the NDK toolchain (gomobile comes from nixpkgs; see packages).
    gomobile init 2>/dev/null || true

    echo "belphegor-mobile shell: go $(go version | awk '{print $3}'), ANDROID_HOME=$ANDROID_HOME"
    echo "build the aar:  scripts/build-aar.sh"
  '';
}

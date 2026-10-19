#!/usr/bin/env bash
# Builds the Go wrapper (walib) into android-app/app/libs/walib.aar with gomobile.
# Needs Go, gomobile (go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init)
# and ANDROID_HOME / ANDROID_NDK_HOME pointing at the Android SDK and NDK.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$root/android-libs" "$root/android-app/app/libs"
cd "$root/walib"
gomobile bind -target=android/arm64 -androidapi 26 -javapkg vbh.ai.whatsapp \
  -o "$root/android-libs/walib.aar" .
cp "$root/android-libs/walib.aar" "$root/android-app/app/libs/walib.aar"
echo "walib.aar -> android-app/app/libs/"

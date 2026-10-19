# WhatsApp for the Light Phone III

An unofficial WhatsApp client for the Light Phone III, built with the Light SDK's UI components.

> [!WARNING]
> Not affiliated with WhatsApp, Meta or Light. It connects as a linked device over a
> reverse-engineered protocol, so WhatsApp may restrict accounts that use it.

## Features

- Chats: text, photos, delivered/seen receipts
- Voice and video calls, front and rear camera
- Call log and contacts

## Install

Download the latest APK from Releases and install it with `adb`:

    adb install whatsapp_<version>.apk

Open the app and scan the QR code from WhatsApp on your main phone (Settings, Linked devices,
Link a device).

## Build

Requires Go, gomobile, the Android SDK with NDK 25.1.8937393, JDK 21, and a checkout of
[light-sdk](https://github.com/lightphone/light-sdk) next to this repository (or set
`LIGHT_SDK_DIR`).

    go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init
    ANDROID_NDK_HOME=$ANDROID_HOME/ndk/25.1.8937393 scripts/build-walib.sh
    cd android-app && ./gradlew assembleDebug

Tests: `./gradlew ktlintCheck testDebugUnitTest` in `android-app`, `go test ./...` in `walib`.

## Release

Push a tag matching `versionName` in `android-app/app/build.gradle.kts`, e.g. `v0.1.0`. The
release workflow builds a signed, minified APK and publishes it. It needs these repository
secrets:

| Secret | Value |
| --- | --- |
| `KEY_STORE_BASE64` | `base64 -i release.jks` |
| `KEY_STORE_PASSWORD` | keystore password |
| `ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

## Layout

- `android-app/` Android app (Kotlin, Compose)
- `walib/` Go library bridging the app to WhatsApp, built with gomobile
- `meowcaller/` vendored [meowcaller](https://github.com/purpshell/meowcaller) with local patches
  (`PATCHES-wa-poc.md`)

## Credits

[hypermeow](https://github.com/polymorfa/hypermeow) (a [whatsmeow](https://github.com/tulir/whatsmeow)
fork), [meowcaller](https://github.com/purpshell/meowcaller),
[Light SDK](https://github.com/lightphone/light-sdk).

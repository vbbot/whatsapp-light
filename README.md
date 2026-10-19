# wa-poc

WhatsApp messaging and calling client for the Light Phone III, as a standalone Android app
styled with the Light SDK's UI components.

## Layout

- `android-app/` — Kotlin / Compose app (UI, audio, camera + H.264 video, notifications).
  Uses `com.thelightphone:sdk-ui` from a sibling checkout at `../../light-sdk`
  (https://github.com/lightphone/light-sdk), included as a Gradle composite build.
- `walib/` — Go wrapper (gomobile) around hypermeow (whatsmeow fork) and meowcaller.
- `meowcaller/` — vendored meowcaller (https://github.com/purpshell/meowcaller) with local
  patches; see `meowcaller/PATCHES-wa-poc.md`.

## Build

Needs Go, gomobile (`go install golang.org/x/mobile/cmd/gomobile@latest && gomobile init`),
the Android SDK + NDK, JDK 21, and the Light SDK checked out at `../../light-sdk`.

    # 1. Go wrapper -> AAR
    cd walib
    ANDROID_HOME=~/Library/Android/sdk ANDROID_NDK_HOME=~/Library/Android/sdk/ndk/25.1.8937393 \
      gomobile bind -target=android/arm64 -androidapi 26 -javapkg vbh.ai.whatsapp -o ../android-libs/walib.aar .
    mkdir -p ../android-app/app/libs && cp ../android-libs/walib.aar ../android-app/app/libs/

    # 2. App
    cd ../android-app && ./gradlew :app:assembleDebug

Session databases (`*.db`) hold WhatsApp linked-device keys and are git-ignored.

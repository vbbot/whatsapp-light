pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // sdk:ui api-exposes com.github.lightphone:light-keyboard (LP3 keyboard + font)
        maven { name = "JitPack"; url = uri("https://jitpack.io") }
    }
}
rootProject.name = "whatsapp"
include(":app")

// Light SDK UI components (LightText, LightTopBar, LightBottomBar, LightTextInputEditor, ...),
// from a checkout of github.com/lightphone/light-sdk. Override the location with
// -PlightSdkDir=... or LIGHT_SDK_DIR; CI checks it out next to this repository.
val lightSdkDir = providers.gradleProperty("lightSdkDir").orNull
    ?: System.getenv("LIGHT_SDK_DIR")
    ?: "../../light-sdk"
includeBuild(lightSdkDir) {
    dependencySubstitution {
        substitute(module("com.thelightphone:sdk-ui")).using(project(":sdk:ui"))
    }
}

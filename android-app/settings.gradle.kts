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
rootProject.name = "wa-poc-android"
include(":app")

// Light SDK UI components (LightText, LightTopBar, LightBottomBar, LightTextInputEditor, ...)
includeBuild("../../light-sdk") {
    dependencySubstitution {
        substitute(module("com.thelightphone:sdk-ui")).using(project(":sdk:ui"))
    }
}

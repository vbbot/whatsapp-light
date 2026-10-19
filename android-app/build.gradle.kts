plugins {
    id("com.android.application") version "8.12.3" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
}

// Same build properties the Light SDK root declares (sdk:ui reads its own root's copy).
ext {
    set("compileSdk", 36)
    set("minSdk", 34)
    set("targetSdk", 34)
    set("jvmTarget", "17")
}

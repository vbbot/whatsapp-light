import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jlleitschuh.gradle.ktlint")
}

ktlint {
    version.set("1.8.0")
    android.set(true)
    verbose.set(true)
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

// Release signing comes from local.properties (storeFile, storePassword, keyAlias, keyPassword).
// The release workflow writes that file from repository secrets.
val localProps =
    Properties().apply {
        rootProject
            .file("local.properties")
            .takeIf { it.exists() }
            ?.inputStream()
            ?.use { load(it) }
    }
val hasReleaseKey = localProps.getProperty("storeFile") != null

android {
    namespace = "vbh.ai.whatsapp"
    compileSdk = 36
    defaultConfig {
        applicationId = "vbh.ai.whatsapp"
        minSdk = 34
        targetSdk = 34
        versionCode = 6
        versionName = "0.2.3"
        ndk { abiFilters += "arm64-v8a" }
    }
    if (hasReleaseKey) {
        signingConfigs {
            create("release") {
                storeFile = file(localProps.getProperty("storeFile"))
                storePassword = localProps.getProperty("storePassword")
                keyAlias = localProps.getProperty("keyAlias")
                keyPassword = localProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging { jniLibs.useLegacyPackaging = true }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint { abortOnError = false }
}

dependencies {
    implementation(files("libs/walib.aar"))
    // Light design language components (Compose + Material3 + LP3 keyboard come in via api)
    implementation("com.thelightphone:sdk-ui")
    implementation("androidx.activity:activity-compose:1.13.0")
    // The composer hosts the LP3 keyboard's view model itself (see Composer.kt), as the SDK's editor does.
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.zxing:core:3.5.3")
    // WhatsApp's own animated stickers are Lottie animations (see Stickers.kt).
    implementation("com.airbnb.android:lottie-compose:6.7.1")

    testImplementation("junit:junit:4.13.2")
    // Real SQLite and org.json for the storage tests.
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.7.0")
}

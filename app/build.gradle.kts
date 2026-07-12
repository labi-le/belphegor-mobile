plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "belphegor.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0" // pin to what the nix (read-only) SDK provides

    defaultConfig {
        applicationId = "belphegor.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // gomobile ships one .so per ABI. Default (release) builds are ARM-only
        // for real phones; x86_64 (emulator/Waydroid) comes from the debug build
        // or from a release built with -Px86only (a Waydroid-installable APK).
        ndk {
            if (project.hasProperty("x86only")) {
                abiFilters += "x86_64"
            } else {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            ndk { abiFilters += "x86_64" }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Produced by scripts/build-aar.sh from belphegor/mobile (QUIC core).
    // Generated Java package: belphegor.mobile (classes Mobile/Config/Node/Handler).
    implementation(files("libs/belphegor.aar"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
}

// Bundle the LSPosed module APK into the app's assets so the app can offer a
// one-tap install. belphegor-mobile is private, so the module can't be fetched
// from its GitHub release at runtime; the debug-signed module APK is installable
// as-is. The app copies it out of assets and fires a package-install intent.
val unlockModuleAssets = layout.buildDirectory.dir("generated/unlockModule")
val bundleUnlockModule =
    tasks.register<Copy>("bundleUnlockModule") {
        dependsOn(":background-clipboard:assembleDebug")
        from(
            project(":background-clipboard").layout.buildDirectory
                .file("outputs/apk/debug/background-clipboard-debug.apk"),
        )
        into(unlockModuleAssets)
        rename { "background-clipboard.apk" }
    }

android.sourceSets.getByName("main").assets.srcDir(unlockModuleAssets)

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(bundleUnlockModule) }

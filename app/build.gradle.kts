plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "belphegor.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0" // pin to what the nix (read-only) SDK provides
    // Must be pinned too: AGP falls back to its own default NDK (27.x as of
    // 8.13), which the nix SDK does not ship, and then silently skips stripping
    // libgojni.so -- that alone added ~10 MB to the release APK.
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "belphegor.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.1.3"
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Produced by scripts/build-aar.sh from belphegor/mobile (QUIC core).
    // Generated Java package: belphegor.mobile (classes Mobile/Config/Node/Handler).
    implementation(files("libs/belphegor.aar"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")
}

// Bundle the LSPosed module APK into the app's assets so it can offer a one-tap
// install without a separate download. The debug-signed module APK is installable
// as-is; the app copies it out of assets and fires a package-install intent.
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

// Passing the task (not just its dir) wires the dependency for every asset
// consumer -- merge, lint, package -- so release lint does not trip Gradle's
// implicit-dependency check.
android.sourceSets.getByName("main").assets.srcDir(bundleUnlockModule)

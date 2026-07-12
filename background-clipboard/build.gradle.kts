plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// LSPosed module (a normal APK that LSPosed activates). It only makes sense on
// a rooted device running Magisk + LSPosed (Zygisk). It grants the belphegor
// app background clipboard access by hooking the framework guard.
android {
    namespace = "belphegor.background"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "belphegor.background"
        minSdk = 29 // the background-clipboard restriction starts at Android 10
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Provided by the LSPosed/Xposed framework at runtime; not bundled.
    // Classic API (repo added in settings.gradle.kts). Modern alternative:
    // io.github.libxposed:api.
    compileOnly("de.robv.android.xposed:api:82")
}

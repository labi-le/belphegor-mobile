plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// LSPosed module (a normal APK that LSPosed activates). It only makes sense on
// a rooted device running Magisk + LSPosed (Zygisk).
android {
    namespace = "belphegor.background"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "belphegor.background"
        minSdk = 29 // the background-clipboard restriction starts at Android 10
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release { isMinifyEnabled = false }
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
    // Provided by the LSPosed/Xposed framework at runtime; not bundled.
    // Classic API (repo added in settings.gradle.kts). Modern alternative:
    // io.github.libxposed:api.
    compileOnly("de.robv.android.xposed:api:82")
}

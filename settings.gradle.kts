pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Xposed API for the :background-clipboard module (compileOnly).
        maven { url = uri("https://api.xposed.info/") }
    }
}

rootProject.name = "belphegor-mobile"
include(":app")
include(":background-clipboard")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // LibretroDroid is published through JitPack.
        maven("https://jitpack.io")
    }
}

rootProject.name = "kof-arcade"

include(":app")

// The move engine, game list and rosters: plain Kotlin, its own build so it
// can be tested without the Android SDK. The app depends on
// "com.kofarcade:core", which this substitutes.
includeBuild("core")

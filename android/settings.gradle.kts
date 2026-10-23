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
    }
}

rootProject.name = "90s-kid-arcade"

include(":app")

// LibretroDroid with a RAM-reading API added (see libretrodroid/build.gradle.kts).
include(":libretrodroid")

// The move engine, game list and rosters: plain Kotlin, its own build so it
// can be tested without the Android SDK. The app depends on
// "com.kofarcade:core", which this substitutes.
includeBuild("core")

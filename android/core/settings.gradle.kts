// :core builds on its own (no Android SDK needed), so the move engine can be
// tested anywhere: `cd android/core && gradle jvmTest`. The app pulls it in as an
// included build (see ../settings.gradle.kts).
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

rootProject.name = "core"

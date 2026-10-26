import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
}

group = "com.kofarcade"
version = "1.0"

// The move engine, game list, rosters and multiplayer: shared by the Android
// app (jvm) and the iPhone app (ios*, built on macOS). What still needs the
// JVM (the room code, ROM repacking) is in jvmMain until it's ported.
kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        testRuns["test"].executionTask.configure { useJUnitPlatform() }
    }
    iosArm64()
    iosSimulatorArm64()
    // Not shipped: runs the iPhone's code (POSIX sockets, threads) in tests on Linux.
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

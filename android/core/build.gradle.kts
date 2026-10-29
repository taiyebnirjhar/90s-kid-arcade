import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
}

group = "com.kofarcade"
version = "1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // Online signaling: Supabase Realtime is a WebSocket (OkHttp's, as Android apps use).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(kotlin("test"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Real WebRTC for the transport's tests (Google's library, its desktop build; Linux x86-64, as CI runs).
    testImplementation("dev.onvoid.webrtc:webrtc-java:0.14.0")
    testRuntimeOnly("dev.onvoid.webrtc:webrtc-java:0.14.0:linux-x86_64")
}

tasks.test {
    useJUnitPlatform()
    // Say why a test failed, in the build's own output (CI shows nothing else).
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
    environment("KOF_SOAK_SEEDS", System.getenv("KOF_SOAK_SEEDS") ?: "1")
}

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // Screenshot tests: renders the Compose screens to PNGs on the build
    // machine (CI publishes them to the ui-snapshots branch).
    id("app.cash.paparazzi") version "1.3.5"
}

android {
    namespace = "com.kofarcade"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kofarcade"
        minSdk = 26
        targetSdk = 35
        // The build's number (CI: the workflow's run number, always rising), so each
        // APK installs over the last as a newer one and says which it is: 1.0.231.
        // A build on a branch is marked as a test; one on a computer is "dev".
        val build = System.getenv("APP_BUILD")?.toIntOrNull()
        versionCode = build ?: 1
        versionName = when {
            build == null -> "1.0-dev"
            System.getenv("APP_CHANNEL").isNullOrEmpty() -> "1.0.$build"
            else -> "1.0.$build-${System.getenv("APP_CHANNEL")}"
        }
        // Online play's server (docs/online-setup.md): the project's address and
        // its public key. Only what the database's rules let a guest do is
        // possible with it; the secret keys never leave the server.
        buildConfigField("String", "SUPABASE_URL", "\"https://ysbskjtvccdbhnfuccso.supabase.co\"")
        buildConfigField("String", "SUPABASE_KEY", "\"sb_publishable_ZdWmdg-2_f5_rvnzhiviYQ_VSUnZPIE\"")
        ndk {
            // The FBNeo core is downloaded for these ABIs (see the CI workflow).
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        // A fixed debug key, so each new APK installs over the previous one
        // instead of asking you to uninstall first. It only signs this
        // personal debug build; it is not a release key.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // The games' pictures, fetched when the app is built (fetchGameArt), never at run time.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/gameArt"))

    packaging {
        jniLibs {
            // libretro cores are loaded with dlopen from the native library
            // directory, so they must be extracted, and stripping can break them.
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/libfbneo_libretro_android.so")
        }
    }
}

dependencies {
    implementation("com.kofarcade:core:1.0")
    implementation(project(":libretrodroid"))

    // Internet rooms: Google's WebRTC, prebuilt (docs/online-plan.md, D1; about 10 MB for our two ABIs).
    implementation("io.getstream:stream-webrtc-android:1.3.10")
    // Internet rooms' QR codes (the encoder only: about 0.5 MB).
    implementation("com.google.zxing:core:3.5.3")
    // Scanning a friend's room QR code in the app: Google's scanner, run by Play services
    // (no camera permission for this app, nothing of the picture kept; free).
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // Core's online signaling uses it (Supabase Realtime's WebSocket).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
}

// The games' pictures (title and play screens, app/art.txt) go inside the
// app: fetched here once from libretro's thumbnail collection (the set
// RetroArch uses) into build/, never committed (they're the games' own
// pictures) and never downloaded by the app. One that libretro hasn't got
// is skipped (the app shows the game's main picture); on CI, any other
// failure fails the build, so no APK ships without them.
val fetchGameArt by tasks.registering {
    val list = file("art.txt")
    val out = layout.buildDirectory.dir("generated/gameArt/art")
    inputs.file(list)
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile
        val base = "https://raw.githubusercontent.com/libretro-thumbnails/FBNeo_-_Arcade_Games/master/"
        val failed = ArrayList<String>()
        for (path in list.readLines().map { it.trim() }.filter { it.isNotEmpty() }) {
            val target = File(dir, path)
            if (target.isFile && target.length() > 0) continue
            target.parentFile.mkdirs()
            val (folder, name) = path.split('/', limit = 2)
            val url = base + folder + "/" + URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            try {
                val conn = URI(url).toURL().openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                when (conn.responseCode) {
                    200 -> {
                        val tmp = File(target.path + ".part")
                        conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                        tmp.renameTo(target)
                    }
                    404 -> logger.lifecycle("No picture upstream (the game's main one shows): $path")
                    else -> failed += "$path: HTTP ${conn.responseCode}"
                }
            } catch (e: Exception) {
                failed += "$path: ${e.message}"
            }
        }
        if (failed.isNotEmpty()) {
            val text = "Couldn't fetch the games' pictures:\n" + failed.joinToString("\n")
            if (System.getenv("CI") != null) throw GradleException(text) else logger.warn(text)
        }
    }
}

tasks.named("preBuild") { dependsOn(fetchGameArt) }

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.kofarcade"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kofarcade"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
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
    }

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
    implementation("com.github.Swordfish90:LibretroDroid:0.14.0")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // Game pictures from libretro's thumbnail collection, cached on the phone.
    implementation("io.coil-kt:coil-compose:2.7.0")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kyubiez96.kurogba"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kyubiez96.kurogba"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            // The two .so files (mGBA core + KuroGBA host) are prebuilt by
            // tools/build-native.sh into src/main/jniLibs/<abi>/ before
            // Gradle runs — see the CI workflow. Gradle only packages them.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        // Sideload trick (same as KuroPatch v2): release builds are signed
        // with the standard Android *debug* key so anyone can build and
        // sideload the exact same APK from source. The keystore is NEVER
        // committed; CI generates a fresh one. Override the location with
        // the KUROGBA_KEYSTORE environment variable if needed.
        create("sideload") {
            storeFile = file(
                System.getenv("KUROGBA_KEYSTORE")
                    ?: "${System.getProperty("user.home")}/.android/debug.keystore"
            )
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("sideload")
            isMinifyEnabled = false
            isShrinkResources = false
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            // The host dlopen()s libmgba_libretro.so by absolute path from
            // nativeLibraryDir, so the libraries must be extracted to real
            // files on disk (legacy packaging), not mmap'd from the APK.
            useLegacyPackaging = true
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs(setOf("src/main/jniLibs"))
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
}

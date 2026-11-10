plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version comes from CI (tag "v1.2.3" -> versionName 1.2.3) or falls back to defaults.
val ciVersionName: String = (System.getenv("DSB_VERSION_NAME") ?: "0.1.0").removePrefix("v")
val ciVersionCode: Int = System.getenv("DSB_VERSION_CODE")?.toIntOrNull() ?: 1

// Optional release signing. Only used when all four env vars are present (GitHub secrets).
val keystorePath: String? = System.getenv("DSB_KEYSTORE_PATH")
val hasSigning = !keystorePath.isNullOrBlank() &&
    !System.getenv("DSB_KEYSTORE_PASSWORD").isNullOrBlank() &&
    !System.getenv("DSB_KEY_ALIAS").isNullOrBlank() &&
    !System.getenv("DSB_KEY_PASSWORD").isNullOrBlank()

android {
    namespace = "dev.deepstellar.browser"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.deepstellar.browser"
        minSdk = 29
        targetSdk = 35
        versionCode = ciVersionCode
        versionName = ciVersionName
        ndk {
            // Torrent engine native libraries. arm64 covers nearly every modern phone,
            // armeabi-v7a covers older ones, x86_64 covers emulators/Chromebooks.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("DSB_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DSB_KEY_ALIAS")
                keyPassword = System.getenv("DSB_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Kept off on purpose: the torrent engine uses JNI/SWIG reflection and
            // shrinking it is a common source of release-only crashes.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
        buildConfig = true
    }
    lint {
        // Lint runs in CI and publishes a report; it doesn't block builds (yet).
        abortOnError = false
        checkReleaseBuilds = false
        htmlReport = true
        xmlReport = false
    }
    testOptions {
        // Unit tests run on a normal JVM: Android classes they touch return defaults instead of crashing.
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

val libtorrent4j = "2.1.0-39"

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.biometric:biometric:1.1.0")
    // Automatic backups: WorkManager schedules them, DocumentFile writes to the folder you pick
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // QR: ZXing (Apache-2.0) for encode/decode, CameraX for the scanner (tap-to-focus, zoom, torch)
    implementation("com.google.zxing:core:3.5.3")
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Torrent engine (libtorrent via libtorrent4j, MIT licensed)
    implementation("org.libtorrent4j:libtorrent4j:$libtorrent4j")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:$libtorrent4j")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:$libtorrent4j")
    implementation("org.libtorrent4j:libtorrent4j-android-x86_64:$libtorrent4j")

    // Test-only: never part of the APK.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json for tests (Android's copy is a stub on a plain JVM)
}

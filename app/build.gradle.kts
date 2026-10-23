plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nshd.jarvis2"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nshd.jarvis2"
        minSdk = 26
        targetSdk = 35
        versionCode = 23
        versionName = "2.0.0"
    }

    // ── Permanent signing (Level 2) ────────────────────────────────────
    // One keystore signs every build (debug + release) so new versions
    // install OVER old ones — no uninstall needed. The keystore itself is
    // never committed: CI decodes it from the JARVIS_KEYSTORE_BASE64
    // secret and passes its location via JARVIS_KEYSTORE_PATH. Local
    // builds without the env vars keep the AGP default (debug) signing.
    signingConfigs {
        val ksPath = System.getenv("JARVIS_KEYSTORE_PATH")
        if (!ksPath.isNullOrBlank() && file(ksPath).exists()) {
            create("permanent") {
                storeFile = file(ksPath)
                storePassword = System.getenv("JARVIS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("JARVIS_KEY_ALIAS")
                keyPassword = System.getenv("JARVIS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        val permanent = signingConfigs.findByName("permanent")
        release {
            if (permanent != null) signingConfig = permanent
        }
        debug {
            if (permanent != null) signingConfig = permanent
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    // Level 2: encrypted API-key storage + explicit coroutines
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Level 6: offline wake word — Vosk Android AAR (JNI recognizer + model
    // downloaded on demand, never bundled). Version 0.3.75 is the newest
    // 0.3.x published to Maven Central (com.alphacephei:vosk-android).
    implementation("com.alphacephei:vosk-android:0.3.75")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}

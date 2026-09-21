import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// The backend address can be overridden per machine in local.properties (git-ignored).
// The NVIDIA key never ships in the APK: every AI call goes through the backend.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun local(key: String, default: String = "") = localProperties.getProperty(key, default).trim()

// Firebase: with google-services.json in android/app/ the Google Services plugin configures everything.
// Without it, FIREBASE_* values from local.properties are used (see FirebaseAuthClient).
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.triplethreats.masteria"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.triplethreats.masteria"
        minSdk = 26
        targetSdk = 36
        // CI passes VERSION_CODE (run number) and VERSION_NAME (from the git tag).
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME")?.removePrefix("v") ?: "1.0.0"
        buildConfigField(
            "String",
            "BACKEND_URL",
            "\"${local("BACKEND_URL", "http://10.0.2.2:8080")}\""
        )
        buildConfigField("String", "FIREBASE_API_KEY", "\"${local("FIREBASE_API_KEY")}\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${local("FIREBASE_APP_ID")}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${local("FIREBASE_PROJECT_ID")}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${local("GOOGLE_WEB_CLIENT_ID")}\"")
    }

    // Release signing: env vars (CI) or local.properties (RELEASE_STORE_FILE, ...). Keystore files are git-ignored.
    val releaseStore = System.getenv("ANDROID_KEYSTORE_PATH") ?: local("RELEASE_STORE_FILE").ifBlank { null }
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: local("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: local("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: local("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Real release key when configured; otherwise the debug key so a local release build still installs.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-process:2.11.0") // foreground/background → realtime socket

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.navigation:navigation-compose:2.10.1")
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    // Networking: plain OkHttp + kotlinx.serialization, including the mentor's SSE stream
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Firebase Authentication (email/password + Google via Credential Manager)
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    // Backdrop blur for the glass surfaces (tab bar, nav bar, sheets)
    implementation("dev.chrisbanes.haze:haze:1.7.3")

    // Scan-to-Quest: CameraX viewfinder + on-device OCR before anything is sent to NIM
    val camerax = "1.6.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
}

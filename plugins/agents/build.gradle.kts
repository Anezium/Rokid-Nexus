plugins {
    id("com.android.application")
}

apply(from = rootProject.file("gradle/plugin-release-signing.gradle"))

android {
    namespace = "com.anezium.rokidbus.plugin.agents"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.anezium.rokidbus.plugin.agents"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":bus-client"))
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.security:security-crypto:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Official iroh Kotlin bindings. Android consumers use the AAR so
    // libiroh_ffi.so and IrohAndroid ship; that AAR api-depends on
    // computer.iroh:iroh (Apache-2.0 / MIT).
    implementation("computer.iroh:iroh-android:1.1.0")
    // No QR-scan precedent in this repo. zxing-android-embedded is a single
    // CaptureActivity; CAMERA is requested at the point of use. Paste is the
    // no-camera fallback.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.json:json:20240303")
}

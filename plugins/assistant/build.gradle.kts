plugins {
    id("com.android.application")
}

apply(from = rootProject.file("gradle/plugin-release-signing.gradle"))

android {
    namespace = "com.anezium.rokidbus.plugin.assistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.anezium.rokidbus.plugin.assistant"
        minSdk = 30
        targetSdk = 36
        versionCode = 17
        versionName = "1.4.8"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ML Kit's recognizer is a native library; one ABI keeps it from multiplying the APK.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    sourceSets {
        getByName("test").resources.srcDir("src/main/assets")
    }
}

dependencies {
    implementation(project(":bus-client"))
    implementation(project(":shared"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Text extraction only; BouncyCastle serves certificate-encrypted PDFs, which Workspace reports as protected.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") {
        exclude(group = "org.bouncycastle")
    }
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation(project(":ink-engine"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.json:json:20240303")
}

plugins {
    id("com.android.application")
}

android {
    namespace = "com.anezium.rokidbus.lens"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.anezium.rokidbus.lens"
        minSdk = 31
        targetSdk = 32
        versionCode = 25
        versionName = "0.1.24-lens-m5"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        // YodaOS-Sprite compatibility is intentionally pinned below current Play policy.
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation(project(":bus-client"))
    implementation(project(":shared"))

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.camera:camera-core:1.3.1")
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")

    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")

    testImplementation("junit:junit:4.13.2")
}

plugins {
    id("com.android.application")
}

android {
    namespace = "com.anezium.rokidbus.glasses"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.anezium.rokidbus.glasses"
        minSdk = 31
        targetSdk = 32
        versionCode = 1
        versionName = "0.1.0-round-a"
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
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":bus-client"))
    implementation("com.rokid.cxr:cxr-service-bridge:1.0-20260522.063600-105")
    testImplementation("junit:junit:4.13.2")
}

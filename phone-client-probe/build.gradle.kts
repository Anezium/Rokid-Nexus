plugins {
    id("com.android.application")
}

android {
    namespace = "com.anezium.rokidbus.phoneprobe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.anezium.rokidbus.phoneprobe"
        minSdk = 31
        targetSdk = 36
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
    implementation(project(":bus-client"))
}

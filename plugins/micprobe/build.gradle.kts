plugins {
    id("com.android.application")
}

android {
    namespace = "com.anezium.rokidbus.plugin.micprobe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.anezium.rokidbus.plugin.micprobe"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":bus-client"))
}

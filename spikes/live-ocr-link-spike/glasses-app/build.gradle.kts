plugins { id("com.android.application") }

android {
    namespace = "com.anezium.liveocr.glasses"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.anezium.liveocr.glasses"
        minSdk = 31
        targetSdk = 32
        versionCode = 1
        versionName = "0.1.0-spike"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lint { disable += "ExpiredTargetSdkVersion" }
}

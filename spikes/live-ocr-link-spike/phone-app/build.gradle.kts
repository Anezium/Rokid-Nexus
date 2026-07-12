plugins { id("com.android.application") }

android {
    namespace = "com.anezium.liveocr.phone"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.anezium.liveocr.phone"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-spike"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("androidx.core:core-ktx:1.13.1")
}

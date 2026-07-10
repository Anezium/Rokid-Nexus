plugins {
    id("com.android.library")
}

android {
    namespace = "com.anezium.rokidbus.media"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":bus-client"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

plugins {
    id("com.android.application")
}

apply(from = rootProject.file("gradle/plugin-release-signing.gradle"))

android {
    namespace = "com.anezium.rokidbus.plugin.transit"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.anezium.rokidbus.plugin.transit"
        minSdk = 30
        targetSdk = 36
        versionCode = 4
        versionName = "1.0.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":bus-client"))
    implementation("androidx.core:core:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.json:json:20240303")
}

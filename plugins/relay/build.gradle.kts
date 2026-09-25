plugins {
    id("com.android.application")
}

apply(from = rootProject.file("gradle/plugin-release-signing.gradle"))

android {
    namespace = "com.anezium.rokidbus.plugin.relay"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.anezium.rokidbus.plugin.relay"
        minSdk = 30
        targetSdk = 36
        versionCode = 13
        versionName = "1.2.4"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":bus-client"))
    testImplementation("junit:junit:4.13.2")
}

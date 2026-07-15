plugins {
    id("com.android.application")
}

apply(from = rootProject.file("gradle/plugin-release-signing.gradle"))

android {
    namespace = "com.anezium.rokidbus.plugin.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.anezium.rokidbus.plugin.sample"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

val sdkVersion = providers.gradleProperty("versionName").orElse("0.1.0-SNAPSHOT")
val usePublishedSdk = providers.gradleProperty("usePublishedSdk")
    .map(String::toBoolean)
    .orElse(false)

dependencies {
    if (usePublishedSdk.get()) {
        implementation("com.github.Anezium.Rokid-Nexus:bus-client:${sdkVersion.get()}")
    } else {
        implementation(project(":bus-client"))
    }
    testImplementation("junit:junit:4.13.2")
}

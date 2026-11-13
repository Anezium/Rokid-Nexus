plugins {
    id("com.android.application")
}

apply(from = rootProject.file("gradle/plugin-release-signing.gradle"))

android {
    namespace = "com.anezium.rokidbus.plugin.meta"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.anezium.rokidbus.plugin.metamessages"
        minSdk = 30
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

val verifyNativeEngine by tasks.registering {
    doLast {
        check(file("libs/metamessages.aar").isFile) {
            "Embedded metamessages AAR is missing. Run native/Build-Android.ps1 with the provisioned Go, gomobile and Android NDK toolchains."
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyNativeEngine) }

dependencies {
    implementation(project(":bus-client"))
    implementation(files("libs/metamessages.aar"))
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

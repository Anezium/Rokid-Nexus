plugins {
    id("com.android.application")
}

apply(from = rootProject.file("gradle/plugin-release-signing.gradle"))

android {
    namespace = "com.anezium.rokidbus.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.anezium.rokidbus.phone"
        minSdk = 31
        targetSdk = 36
        versionCode = 10029
        versionName = "1.0.29"
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

    packaging {
        resources {
            excludes += "META-INF/versions/**"
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":bus-client"))
    implementation("androidx.activity:activity:1.10.1")
    implementation("com.example.cxrglobal:lib:0.2.0")
    implementation("com.flyfishxu:kadb:2.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.13")
}

plugins {
    id("com.android.application")
}

android {
    namespace = "com.anezium.rokidbus.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.anezium.rokidbus.phone"
        minSdk = 31
        targetSdk = 36
        versionCode = 5
        versionName = "0.1.4-lens-m4"
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
    implementation(project(":shared"))
    implementation(project(":bus-client"))
    implementation(project(":plugin-lyrics"))
    implementation(project(":plugin-media"))
    implementation(project(":plugin-transit"))
    implementation("com.example.cxrglobal:lib:0.2.0")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

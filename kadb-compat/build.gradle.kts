import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Use the Kotlin plugin already supplied on AGP's classpath.
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    androidLibrary {
        namespace = "com.flyfishxu.kadb"
        compileSdk = 36
        minSdk = 23
        withHostTestBuilder {}
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.bouncycastle:bcprov-jdk18on:1.83")
            implementation("org.bouncycastle:bcpkix-jdk18on:1.83")
            api("com.squareup.okio:okio:3.17.0")
        }
        val androidHostTest by getting {
            dependencies { implementation("junit:junit:4.13.2") }
        }
        androidMain.dependencies {
            implementation("com.github.Flyfish233:spake2-java:1.0.5")
            implementation("androidx.documentfile:documentfile:1.1.0")
            implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
        }
    }
}

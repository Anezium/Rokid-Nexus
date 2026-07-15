plugins {
    id("com.android.library")
    id("maven-publish")
}

group = "com.github.Anezium.Rokid-Nexus"
version = providers.gradleProperty("versionName").orElse("0.1.0-SNAPSHOT").get()

android {
    namespace = "com.anezium.rokidbus.client"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(project(":shared"))
    implementation("androidx.core:core:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "bus-client"
                pom {
                    name.set("Rokid Nexus Bus Client")
                    description.set("Rokid Nexus plugin SDK: bus client, plugin service base classes, and the NexusUi kit")
                    url.set("https://github.com/Anezium/Rokid-Nexus")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("Anezium")
                            name.set("Anezium")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/Anezium/Rokid-Nexus.git")
                        developerConnection.set("scm:git:ssh://git@github.com/Anezium/Rokid-Nexus.git")
                        url.set("https://github.com/Anezium/Rokid-Nexus")
                    }
                }
            }
        }
    }
}

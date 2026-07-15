plugins {
    id("com.android.library")
    id("maven-publish")
}

group = "com.github.Anezium.Rokid-Nexus"
version = providers.gradleProperty("versionName").orElse("0.1.0-SNAPSHOT").get()

android {
    namespace = "com.anezium.rokidbus.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
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
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "shared"
                pom {
                    name.set("Rokid Nexus Shared")
                    description.set("Rokid Nexus shared wire contracts and constants")
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

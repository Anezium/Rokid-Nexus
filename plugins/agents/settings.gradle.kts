pluginManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://maven.rokid.com/repository/maven-public/") }
    }
}

rootProject.name = "NexusAgentsPlugin"

include(":shared")
include(":bus-client")
include(":ink-engine")
project(":shared").projectDir = file("../../shared")
project(":bus-client").projectDir = file("../../bus-client")
project(":ink-engine").projectDir = file("../../ink-engine")

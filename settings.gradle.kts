pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.rokid.com/repository/maven-public/") }
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "RokidNexus"
include(":shared")
include(":bus-client")
include(":plugin-lyrics")
include(":plugin-media")
include(":plugin-transit")
include(":phone-hub")
include(":glasses-hub")
include(":phone-client-probe")
include(":glasses-client-probe")
include(":lens-glasses")

includeBuild("../CxrGlobal") {
    dependencySubstitution {
        substitute(module("com.example.cxrglobal:lib")).using(project(":lib"))
    }
}

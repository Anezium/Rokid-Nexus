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
        maven { url = uri("https://maven.rokid.com/repository/maven-public/") }
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
include(":plugin-sample")

val cxrGlobalDirectory = file("../CxrGlobal")
val skipCxrGlobal = providers.gradleProperty("skipCxrGlobal")
    .map(String::toBoolean)
    .orElse(false)
    .get()
if (!skipCxrGlobal && cxrGlobalDirectory.isDirectory) {
    includeBuild(cxrGlobalDirectory) {
        dependencySubstitution {
            substitute(module("com.example.cxrglobal:lib")).using(project(":lib"))
        }
    }
}

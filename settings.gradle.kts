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

rootProject.name = "RokidNexus"
include(":shared")
include(":bus-client")
include(":plugin-lyrics")
include(":plugin-media")
include(":plugin-transit")
include(":plugin-feeds")
include(":phone-hub")
include(":glasses-hub")
include(":phone-client-probe")
include(":glasses-client-probe")
include(":plugin-sample")

// Plugin modules live under plugins/ (one folder per plugin, each with its own
// README and CHANGELOG); feeds moves there once the in-flight feeds branch lands.
project(":plugin-lyrics").projectDir = file("plugins/lyrics")
project(":plugin-media").projectDir = file("plugins/media")
project(":plugin-transit").projectDir = file("plugins/transit")
project(":plugin-sample").projectDir = file("plugins/sample")

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

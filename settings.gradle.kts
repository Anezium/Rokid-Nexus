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
        maven {
            url = uri("https://maven.pkg.github.com/MorpheApp/registry")
            // GitHub Packages rejects anonymous reads even of public packages. CI passes
            // GITHUB_ACTOR/GITHUB_TOKEN; developer machines may set gpr.user/gpr.key.
            val packagesUser = listOf(providers.environmentVariable("GITHUB_ACTOR"), providers.gradleProperty("gpr.user"))
                .firstNotNullOfOrNull { it.orNull?.takeIf(String::isNotBlank) }
            val packagesKey = listOf(providers.environmentVariable("GITHUB_TOKEN"), providers.gradleProperty("gpr.key"))
                .firstNotNullOfOrNull { it.orNull?.takeIf(String::isNotBlank) }
            if (packagesUser != null && packagesKey != null) {
                credentials {
                    username = packagesUser
                    password = packagesKey
                }
            }
            content {
                includeGroup("app.morphe")
            }
        }
        maven { url = uri("https://maven.rokid.com/repository/maven-public/") }
    }
}

rootProject.name = "RokidNexus"
include(":shared")
include(":bus-client")
include(":plugin-lyrics")
include(":plugin-media")
include(":plugin-transit")
include(":plugin-tasker")
include(":plugin-assistant")
include(":plugin-lens")
include(":plugin-photosync")
include(":plugin-relay")
include(":plugin-nav")
include(":plugin-feeds")
include(":phone-hub")
include(":glasses-hub")
include(":phone-client-probe")
include(":glasses-client-probe")
include(":plugin-sample")
include(":plugin-wireless-adb")
include(":ink-engine")
include(":kadb-compat")
include(":plugin-patcher")
project(":plugin-patcher").projectDir = file("plugins/patcher")

// Plugin modules live under plugins/ (one folder per plugin, each with its own
// README and CHANGELOG); feeds moves there once the in-flight feeds branch lands.
project(":plugin-lyrics").projectDir = file("plugins/lyrics")
project(":plugin-media").projectDir = file("plugins/media")
project(":plugin-transit").projectDir = file("plugins/transit")
project(":plugin-tasker").projectDir = file("plugins/tasker")
project(":plugin-assistant").projectDir = file("plugins/assistant")
project(":plugin-lens").projectDir = file("plugins/lens")
project(":plugin-photosync").projectDir = file("plugins/photosync")
project(":plugin-relay").projectDir = file("plugins/relay")
project(":plugin-nav").projectDir = file("plugins/nav")
project(":plugin-sample").projectDir = file("plugins/sample")
project(":plugin-wireless-adb").projectDir = file("plugins/wireless-adb")

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

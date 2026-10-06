plugins { id("com.android.application") }
apply(from = rootProject.file("gradle/plugin-release-signing.gradle"))
android {
    namespace = "com.anezium.rokidbus.plugin.patcher"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.anezium.rokidbus.plugin.patcher"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Merges win over AGP's default excludes, so dependency licences and notices
    // (including the patcher's GPLv3 section 7 notice) are concatenated, not dropped.
    packaging.resources.excludes += "META-INF/versions/**"
    packaging.resources.merges += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "LICENSE*", "NOTICE*")
    sourceSets["main"].assets.directories += layout.buildDirectory.dir("generated/patch-assets").get().asFile.path
    testOptions.unitTests.all {
        it.systemProperty("preparedBundle", layout.buildDirectory.file("generated/patch-assets/bundled.mpp").get().asFile.path)
        it.systemProperty("patchBundleFixture", providers.gradleProperty("patchBundleInput").orElse(layout.buildDirectory.file("patch-source/source.mpp").get().asFile.path).get())
    }
    testOptions.unitTests.isIncludeAndroidResources = true
}
val patcherRuntime by configurations.creating
configurations["implementation"].extendsFrom(patcherRuntime)
val patchBundleClasspath by configurations.creating
configurations.matching { it.name.endsWith("RuntimeClasspath") || it.name == "patchBundleClasspath" }.configureEach {
    resolutionStrategy.force("org.bouncycastle:bcpkix-jdk18on:1.79", "org.bouncycastle:bcprov-jdk18on:1.79", "org.bouncycastle:bcutil-jdk18on:1.79")
}
dependencies {
    implementation(project(":bus-client"))
    implementation("androidx.core:core:1.17.0")
    patcherRuntime("app.morphe:morphe-patcher:1.7.0")
    patchBundleClasspath("app.morphe:morphe-patcher:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.79")
    implementation("com.android.tools.build:apksig:9.1.1")
    implementation("com.github.REAndroid:arsclib:a28c6fb2a7")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
}
// Published rokid.2 is a JVM JAR, not an Android bundle. Convert at build time,
// never pretend the JVM class files can be executed by Android.
val prepareAndroidBundle by tasks.registering(Exec::class) {
    val output = layout.buildDirectory.dir("generated/patch-assets")
    outputs.dir(output)
    outputs.file(layout.buildDirectory.file("patch-source/source.mpp"))
    inputs.files(patchBundleClasspath)
    // Follow the build-tools and platform AGP uses. inputs.files tolerates a missing
    // component so doFirst can name it instead of failing task validation.
    val sdkDirectory = androidComponents.sdkComponents.sdkDirectory.get().asFile
    val buildTools = android.buildToolsVersion
    val platform = "android-${android.compileSdk}"
    val d8Jar = File(sdkDirectory, "build-tools/$buildTools/lib/d8.jar")
    val androidJar = File(sdkDirectory, "platforms/$platform/android.jar")
    inputs.files(d8Jar, androidJar)
    val input = providers.gradleProperty("patchBundleInput")
    if (input.isPresent) inputs.file(input.get())
    inputs.file("scripts/prepare_bundle.py")
    val windows = System.getProperty("os.name").startsWith("Windows")
    val python = providers.gradleProperty("pythonExecutable").orElse(if (windows) "python" else "python3")
    doFirst {
        val missing = mapOf("build-tools;$buildTools" to d8Jar, "platforms;$platform" to androidJar).filterValues { !it.isFile }.keys
        if (missing.isNotEmpty()) {
            throw GradleException("prepareAndroidBundle needs the Android SDK components $missing in $sdkDirectory. " +
                "Install them with: sdkmanager ${missing.joinToString(" ") { "\"$it\"" }}")
        }
        commandLine(python.get(), file("scripts/prepare_bundle.py"),
            "--input", input.orNull ?: "",
            "--output", output.get().asFile,
            "--java", File(System.getProperty("java.home"), if (windows) "bin/java.exe" else "bin/java"),
            "--d8", d8Jar,
            "--android", androidJar,
            "--classpath", patchBundleClasspath.files.joinToString(File.pathSeparator))
    }
}
tasks.named("preBuild").configure { dependsOn(prepareAndroidBundle) }

plugins {
    id("com.android.application") version "9.2.1" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

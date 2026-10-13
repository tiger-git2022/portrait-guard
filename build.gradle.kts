plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

// Keep generated files outside OneDrive so its sync process cannot lock resource outputs mid-build.
val localBuildRoot = file(System.getProperty("user.home"))
    .resolve(".gradle/project-builds/portrait-guard")

layout.buildDirectory.set(localBuildRoot.resolve("root"))
subprojects {
    layout.buildDirectory.set(localBuildRoot.resolve(name))
}

// https://developer.android.com/build#settings-file
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        mavenLocal()
        maven("https://jitpack.io")
    }
}

rootProject.name = "MISSA_TV_360_PLUS"
include(":app", ":shared", ":library")

// Skip heavy Desktop (Skiko) and Dokka multi-module setups during standard Android Studio sync
// unless explicitly requested via Gradle tasks or -PenableDocs=true / -PenableDesktop=true.
val requestedTasks = gradle.startParameter.taskNames
val enableDocs = providers.gradleProperty("enableDocs").orNull?.toBoolean() == true ||
    requestedTasks.any {
        it.contains("dokka", ignoreCase = true) ||
            it.startsWith("docs:") ||
            it.startsWith(":docs:")
    }
val enableDesktop = providers.gradleProperty("enableDesktop").orNull?.toBoolean() == true ||
    requestedTasks.any { it.contains("desktop", ignoreCase = true) }

if (enableDocs) {
    include(":docs")
}
if (enableDesktop) {
    include(":desktopApp")
}

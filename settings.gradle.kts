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
        // JitPack: libadb-android is not on Maven Central.
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "giraquest"
include(":app")

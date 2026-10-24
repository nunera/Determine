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
    }
}

rootProject.name = "determine-android"

// Pure-Kotlin phone core (wire codec, engine, hazard policy, calibration) lives in ../phone and is
// substituted for `org.determine:core` (group org.determine, project `core`).
includeBuild("../phone")

include(":app")

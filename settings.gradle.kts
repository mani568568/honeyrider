pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal() // This is where the Compose Compiler plugin is hosted
    }
}

// Keep the rest of your settings file as it is
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "HoneyRider" // Or your project name
include(":app")
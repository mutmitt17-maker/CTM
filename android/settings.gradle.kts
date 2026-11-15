rootProject.name = "credit-telephone-android"

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// Jalon 1. Les autres modules (:core-security, :core-network, :app-agent, :app-client) seront ajoutés aux jalons suivants.
include(":core-model")

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

// Jalons 1 et 2. Les autres modules (:core-security, :app-agent, :app-client) seront ajoutés aux jalons suivants.
include(":core-model")
include(":core-network")

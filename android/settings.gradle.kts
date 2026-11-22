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

// Jalons 1-2 (Kotlin pur) + jalons 3-4 (app-agent ViewModels)
include(":core-model")
include(":core-network")
include(":core-security")
include(":app-agent")
include(":agent-http")
// :app-client sera ajouté aux jalons 5-6

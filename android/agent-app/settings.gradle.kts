rootProject.name = "ctm-agent-app"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":app")

// Les modules Kotlin purs sont partagés avec le build de tests (dossier android/) :
// leur code n'est écrit qu'une fois. Ce build-ci n'est lancé que pour fabriquer l'APK.
for (name in listOf("core-model", "core-network", "core-security", "app-agent", "agent-http")) {
    include(":$name")
    project(":$name").projectDir = file("../$name")
}

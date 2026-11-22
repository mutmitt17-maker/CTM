plugins {
    kotlin("jvm")
}

// Couche réseau de l'application agent : parle à Supabase (connexion) et à l'Edge Function `api`.
// Kotlin/JVM pur : testée dans le CI avec un faux serveur, sans émulateur Android.
dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-network"))
    implementation(project(":core-security"))
    implementation(project(":app-agent"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}

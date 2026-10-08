// IVModelKit's port: manifest, chunks, element index, filters and the AR geometry. Pure Kotlin.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":core"))
    api(libs.coroutines.core)
    implementation(libs.okhttp)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test { useJUnitPlatform() }

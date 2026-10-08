// IVAPI's port: OkHttp client, interceptors, auth, visits, analytics. Pure Kotlin.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":core"))
    api(project(":modelkit"))
    api(libs.okhttp)
    api(libs.coroutines.core)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
    // ApiContractTest checks the hand-written models against the committed spec.
    systemProperty("iv.openapi", rootProject.file("openapi/openapi.json").absolutePath)
    inputs.file(rootProject.file("openapi/openapi.json"))
}

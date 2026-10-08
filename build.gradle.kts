// Every plugin is on the root build classpath, so the Kotlin Gradle plugin and AGP share one
// class loader (the Kotlin Android plugin looks AGP up). AGP is left out of JVM-only builds.
buildscript {
    val jvmOnly = providers.gradleProperty("jvmOnly").orNull == "true" ||
        providers.environmentVariable("IV_JVM_ONLY").orNull == "1"
    repositories {
        mavenCentral()
        if (!jvmOnly) google()
    }
    dependencies {
        if (!jvmOnly) classpath(libs.agp)
        classpath(libs.kotlin.gradle)
        classpath(libs.kotlin.serialization.gradle)
        classpath(libs.kotlin.compose.gradle)
    }
}

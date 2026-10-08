pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "insiteview-android"

// Module rules (docs/PLAN.md §2, CLAUDE.md): :core, :modelkit and :api are plain Kotlin/JVM;
// the rest are Android modules.
include(":core", ":modelkit", ":api")

val jvmOnly = providers.gradleProperty("jvmOnly").orNull == "true" ||
    providers.environmentVariable("IV_JVM_ONLY").orNull == "1"
if (!jvmOnly) {
    include(":design", ":scene", ":ar", ":features", ":app")
}

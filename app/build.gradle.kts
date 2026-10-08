import java.util.Properties

// The app (App/ on iOS): tabs, sign-in, scanner, profile, App Links. Guest screens are in :features.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Build types mirror the iOS configurations (Config/*.xcconfig on iOS, docs/PLAN.md §1). Until a
// staging environment exists, all three point at production. `local.properties` can override
// `iv.apiBaseUrl` / `iv.webBaseUrl` for the `local` build (a phone can't reach localhost).
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun local(key: String, default: String) = localProperties.getProperty(key) ?: default

android {
    namespace = "com.getinsiteview.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.getinsiteview.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "SENTRY_DSN", "\"\"")
        // Google Play's listing, for "Update" on "Update required".
        buildConfigField("String", "PLAY_STORE_PACKAGE", "\"com.getinsiteview.android\"")
        manifestPlaceholders["appLinkHost"] = "getinsiteview.com"
    }

    signingConfigs {
        create("release") {
            // CI writes the upload keystore from secrets (docs/PLAN.md §5); unset locally.
            val store = System.getenv("IV_UPLOAD_KEYSTORE")
            if (store != null) {
                storeFile = file(store)
                storePassword = System.getenv("IV_UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("IV_UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("IV_UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".local"
            buildConfigField("String", "API_BASE_URL", "\"${local("iv.apiBaseUrl", "https://api.getinsiteview.com")}\"")
            buildConfigField("String", "WEB_BASE_URL", "\"${local("iv.webBaseUrl", "https://getinsiteview.com")}\"")
        }
        create("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".staging"
            matchingFallbacks += "release"
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("String", "API_BASE_URL", "\"https://api.getinsiteview.com\"")
            buildConfigField("String", "WEB_BASE_URL", "\"https://getinsiteview.com\"")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("IV_UPLOAD_KEYSTORE") != null) signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "API_BASE_URL", "\"https://api.getinsiteview.com\"")
            buildConfigField("String", "WEB_BASE_URL", "\"https://getinsiteview.com\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { jniLibs { useLegacyPackaging = false } }
}


dependencies {
    implementation(project(":features"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.browser)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.barcode)
    implementation(libs.install.referrer)
    implementation(libs.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}

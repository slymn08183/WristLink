plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/** A git command's output, or null outside a checkout (e.g. a source zip). */
fun git(vararg args: String): String? = runCatching {
    providers.exec { commandLine("git", *args) }.standardOutput.asText.get().trim()
}.getOrNull()?.takeIf { it.isNotEmpty() }

android {
    namespace = "com.wristlink"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wristlink"
        minSdk = 30
        targetSdk = 34
        // Both come from git, so a local build and the release of the same commit agree and
        // every later commit counts higher (the updater refuses nothing it shouldn't).
        // CI passes the release's own name, e.g. "0.1.10"; a local build between releases is
        // named after the last one, e.g. "0.1.10-3-gabc1234", which the updater sees as 0.1.10.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull()
            ?: git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME")
            ?: git("describe", "--tags", "--match", "v*")?.removePrefix("v") ?: "0.0.0-dev"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Installs next to the release app instead of replacing it (different signing key)
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only attach the release signing config when KEYSTORE_PATH is set,
            // so `./gradlew assembleDebug` works locally without a keystore.
            if (!System.getenv("KEYSTORE_PATH").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Compose BOM
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    debugImplementation(libs.compose.ui.tooling)

    // Wear OS Compose
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)

    // Wear OS Tiles
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.protolayout.material)
    implementation(libs.androidx.concurrent.futures)

    // Media session + Ongoing Activity
    implementation(libs.androidx.media)
    implementation(libs.wear.ongoing)
    // Ambient mode (AmbientLifecycleObserver) for the workout screens
    implementation(libs.wear)
    implementation(libs.androidx.fragment)

    // Nordic BLE
    implementation(libs.nordic.ble.ktx)
    implementation(libs.nordic.ble.common)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)

    // Health Services: passive steps, heart rate, calories, sleep state
    implementation(libs.health.services.client)

    // Weather and sunrise/sunset complications
    implementation(libs.wear.complications.data.source.ktx)

    // WristLink watch face
    implementation(libs.wear.watchface)
    implementation(libs.wear.watchface.complications.rendering)
    implementation(libs.wear.watchface.editor)

    testImplementation(libs.junit)
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Version: single source of truth is gradle.properties.
// versionName is derived here and exposed to Kotlin via BuildConfig.VERSION_NAME.
val versionMajor = project.property("VERSION_MAJOR").toString().toInt()
val versionMinor = project.property("VERSION_MINOR").toString().toInt()
val versionPatch = project.property("VERSION_PATCH").toString().toInt()

// Release signing is only wired up when a keystore AND all credentials are present.
// Otherwise the release build fails loudly rather than silently emitting an unsigned APK.
val keystoreFile = file("keystore.jks")
val signingStorePassword: String? = System.getenv("ANDROID_SIGNING_STORE_PASSWORD")
val signingKeyAlias: String? = System.getenv("ANDROID_SIGNING_KEY_ALIAS")
val signingKeyPassword: String? = System.getenv("ANDROID_SIGNING_KEY_PASSWORD")
val hasReleaseSigning = keystoreFile.exists() &&
    !signingStorePassword.isNullOrBlank() &&
    !signingKeyAlias.isNullOrBlank() &&
    !signingKeyPassword.isNullOrBlank()

android {
    namespace = "com.looptimer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.looptimer"
        minSdk = 26
        targetSdk = 35
        versionCode = versionMajor * 10000 + versionMinor * 100 + versionPatch
        versionName = "$versionMajor.$versionMinor.$versionPatch"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = keystoreFile
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // Minification is intentionally disabled for now: the previous ProGuard config
            // kept every app + Compose class, so it carried all of the crash risk with none
            // of the size benefit. Re-enabled in a later pass with rules that actually shrink.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
            // Lets a debug build coexist with a release install on the same device.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
}

// Fail fast when someone asks for a release build without complete signing credentials,
// instead of shipping an unsigned APK that only breaks at install time.
gradle.taskGraph.whenReady {
    val wantsRelease = allTasks.any { it.project == project && it.name.contains("Release") }
    if (wantsRelease && !hasReleaseSigning) {
        val reason = if (!keystoreFile.exists()) {
            "app/keystore.jks is missing"
        } else {
            "ANDROID_SIGNING_STORE_PASSWORD / ANDROID_SIGNING_KEY_ALIAS / " +
                "ANDROID_SIGNING_KEY_PASSWORD are not all set"
        }
        throw GradleException(
            "Release build requested but signing is not configured ($reason). " +
                "Provide the keystore and credentials, or build the debug variant instead."
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // Real org.json implementation: android.jar stubs it out in unit tests.
    testImplementation("org.json:json:20240303")
}

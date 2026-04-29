plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.looptimer"
    compileSdk = 35

    // 版本号从 gradle.properties 读取
    val major = project.property("VERSION_MAJOR").toString().toInt()
    val minor = project.property("VERSION_MINOR").toString().toInt()
    val patch = project.property("VERSION_PATCH").toString().toInt()
    val versionName = project.property("VERSION_NAME").toString()
    val versionCode = major * 10000 + minor * 100 + patch

    defaultConfig {
        applicationId = "com.looptimer"
        minSdk = 26
        targetSdk = 34
        this.versionCode = versionCode
        this.versionName = versionName
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Release 签名配置
    signingConfigs {
        create("release") {
            // 环境变量: ANDROID_SIGNING_*
            storeFile = file("keystore.jks")
            storePassword = System.getenv("ANDROID_SIGNING_STORE_PASSWORD") ?: ""
            keyAlias = System.getenv("ANDROID_SIGNING_KEY_ALIAS") ?: ""
            keyPassword = System.getenv("ANDROID_SIGNING_KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 如果 keystore 存在则使用签名
            if (file("keystore.jks").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.detekt)
}

// Static analysis for the Kotlin sources. Run with `./gradlew detekt`.
detekt {
    source.setFrom("app/src/main/kotlin", "app/src/test/kotlin")
    config.setFrom("$rootDir/config/detekt.yml")
    buildUponDefaultConfig = true
    parallel = true
}

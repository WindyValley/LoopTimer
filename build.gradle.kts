plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
}

// Static analysis for the Kotlin sources. Run with `./gradlew detekt`.
detekt {
    source.setFrom("app/src/main/kotlin", "app/src/test/kotlin")
    config.setFrom("$rootDir/config/detekt.yml")
    buildUponDefaultConfig = true
    parallel = true
}

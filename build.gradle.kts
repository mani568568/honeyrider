plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false
    // Manually define the compose compiler plugin to force the correct version
    id("org.jetbrains.kotlin.plugin.compose") version libs.versions.kotlin.get() apply false
}
plugins {
    // AGP 9+ has built-in Kotlin support; no org.jetbrains.kotlin.android needed.
    // The Compose compiler plugin is still required and must match AGP's
    // embedded Kotlin version.
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10" apply false
}

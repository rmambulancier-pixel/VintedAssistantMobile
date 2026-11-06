plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    // KSP : génère le code de Room à la compilation. Version alignée sur Kotlin 2.2.20.
    id("com.google.devtools.ksp") version "2.2.20-2.0.4" apply false
    id("androidx.room") version "2.8.4" apply false
}

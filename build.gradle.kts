plugins {
    id("com.android.application") version "9.5.3" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    // KSP : génère le code de Room à la compilation (KSP2, versionné indépendamment de Kotlin).
    id("com.google.devtools.ksp") version "2.3.14" apply false
    id("androidx.room") version "2.8.5" apply false
}

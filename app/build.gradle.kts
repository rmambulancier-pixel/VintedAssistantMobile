plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
}
android {
    namespace = "fr.vintedassistant.mobile"
    compileSdk = 36
    defaultConfig {
        applicationId = "fr.vintedassistant.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 44
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Clé de débogage fixe, versionnée : chaque APK de test a la même signature et
    // s'installe par-dessus la précédente sans perdre les données. Ne sert qu'aux builds de test.
    signingConfigs {
        getByName("debug") {
            storeFile = file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
// kotlinOptions est une erreur depuis Kotlin 2.2 : on passe par compilerOptions.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
// Schéma de la base exporté et versionné : indispensable pour écrire et tester les migrations.
room {
    schemaDirectory("$projectDir/schemas")
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // Rappel quotidien de republication (tâche différable, survit au redémarrage).
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    // IA sur le téléphone : Gemini Nano via ML Kit (gratuit, hors ligne). API en bêta.
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")
    // Détourage des photos sur le téléphone (gratuit ; modèle fourni par Google Play services).
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // Vraie implémentation de org.json pour les tests JVM (celle d'Android n'existe pas hors appareil).
    testImplementation("org.json:json:20240303")

    // Tests sur émulateur : base Room réelle et migration 1 → 2.
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ultrayo.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ultrayo.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 5
        versionName = "0.3.0"
    }

    // Firma fija para que cada versión nueva se instale encima de la anterior
    // sin borrar la memoria ni el modelo descargado.
    signingConfigs {
        getByName("debug") {
            storeFile = file("firma.keystore")
            storePassword = "ultrayo"
            keyAlias = "ultrayo"
            keyPassword = "ultrayo"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
}

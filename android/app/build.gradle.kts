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
        versionCode = 10
        versionName = "0.8.0"
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

    // El motor de imágenes es un programa que se ejecuta aparte: Android solo deja ejecutarlo
    // si se instala descomprimido en la carpeta de librerías nativas.
    packaging {
        jniLibs {
            useLegacyPackaging = true
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
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Oído sin internet para la palabra "Ultra"
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
}

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
        versionCode = 46
        versionName = "0.34.0"
        // Pruebas automáticas en un emulador (GitHub Actions): ver android/scripts/prueba-emulador.sh
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions {
        // Las pruebas rápidas (sin teléfono) usan clases de Android vacías: que devuelvan 0/null en vez de fallar.
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.maxHeapSize = "1g"
            it.testLogging {
                showStandardStreams = true
                events("failed", "skipped")
            }
        }
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
        resources {
            excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES")
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
    // Para pasarle al modelo los datos de las herramientas (ya venía dentro de LiteRT-LM)
    implementation("com.google.code.gson:gson:2.11.0")
    // Motor de habilidades programadas: JavaScript interpretado dentro de la app
    implementation("org.mozilla:rhino:1.7.15")
    // Leer el texto de los PDF
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // Leer el texto de las fotos sin internet (el modelo va dentro de la app)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Descomprimir zip, 7z, tar, gz, bz2 y xz
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9")
    // Zip con contraseña (también AES), RAR4 (también con contraseña) y RAR5 (libarchive nativo)
    implementation("net.lingala.zip4j:zip4j:2.11.6")
    implementation("com.github.junrar:junrar:8.1.2")
    implementation("me.zhanghai.android.libarchive:library:1.1.6")
    // Decompilar el código (dex) de una APK a Java, dentro del teléfono. Sin lo que solo sirve
    // para escribir smali o para la línea de órdenes de jadx.
    implementation("io.github.skylot:jadx-core:1.5.6")
    implementation("io.github.skylot:jadx-dex-input:1.5.6") {
        exclude(group = "com.android.tools.smali")
        exclude(group = "com.google.guava")
        exclude(group = "com.beust")
    }

    // Pruebas rápidas en el ordenador (lógica pura: cuentas, nombres oídos, "Ultra"…)
    testImplementation("junit:junit:4.13.2")
    // Pruebas en el emulador: abre la app, recorre los ajustes y lanza órdenes directas
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

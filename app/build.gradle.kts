plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tisma.scanner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tisma.scanner"
        // API 30: CameraManager.getConcurrentCameraIds() para abrir varias cámaras a la vez.
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Sólo ARM de 64 bits (todos los móviles actuales): ONNX Runtime trae librerías nativas
        // para cada arquitectura y, con todas, el APK pasaba de 23 a ~98 MB.
        // -PwithX86_64 suma x86_64 sólo para la prueba en el emulador (sus imágenes no traducen arm64).
        ndk { abiFilters += listOf("arm64-v8a") + if (project.hasProperty("withX86_64")) listOf("x86_64") else emptyList() }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    // La licencia (AGPL-3.0) y las de terceros van dentro del APK, copiadas de la raíz del repo al compilar
    // (assets/legal/), para mostrarlas en ⚙ → Registro → Acerca de: así nunca quedan distintas de las del repo.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/legal"))
    testOptions {
        unitTests {
            // Robolectric necesita los recursos para renderizar Compose en los tests de pantalla.
            isIncludeAndroidResources = true
            all {
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                it.maxHeapSize = "2g"
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Inferencia de modelos de IA en el móvil (Depth Anything 3 Small, Apache-2.0). ONNX Runtime es MIT.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    // El envío a Tisma Cloud en segundo plano, que se retoma solo al volver la red (Apache-2.0).
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // Firma Ed25519 de lo que se publica en la biblioteca de Tisma (Android la trae recién desde la 13; MIT).
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    testImplementation("junit:junit:4.13.2")
    // Capturas de pantalla de la UI en la JVM (sin emulador).
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

val copyLegal by tasks.registering(Copy::class) {
    from(rootProject.file("LICENSE"), rootProject.file("THIRD_PARTY.md"))
    into(layout.buildDirectory.dir("generated/legal/legal"))
}
tasks.named("preBuild") { dependsOn(copyLegal) }

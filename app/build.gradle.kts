// GiraQuest — build de la app (GPLv3).
// Target API 32 = Android 12L (Quest 3 / Horizon OS).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "es.girarkas.giraquest"
    // compileSdk 34: material 1.11.0 referencia recursos del sistema de API 34
    // (values-v34: android:color/system_*); con compileSdk 32 el link de
    // recursos falla. targetSdk sigue en 32 = Android 12L de la Quest 3.
    compileSdk = 34

    defaultConfig {
        applicationId = "es.girarkas.giraquest"
        minSdk = 30
        targetSdk = 32
        versionCode = 54
        versionName = "0.1.53"
        // v0.1.9: BuildConfig.VRP_API_KEY (cabecera X-API-Key del mirror).
        // En Gradle va vacía; el build manual la inyecta desde .api-key.
        buildConfigField("String", "VRP_API_KEY", "\"\"")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // Red / descargas / corrutinas
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ADB embebido: cliente ADB Java puro con pairing SPAKE2/TLS para Android 11+.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    // TLS 1.3 para el canal ADB (el Conscrypt del sistema no se puede usar
    // sin bypass de hidden API; este es el recomendado por el README de libadb).
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    // Generación del par de claves RSA + certificado autofirmado para ADB.
    implementation("org.bouncycastle:bcprov-jdk15to18:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.78.1")

    // Descompresión de OBB empaquetados (.zip y .7z, como en el PC).
    implementation("org.apache.commons:commons-compress:1.26.1")
    // v0.1.8: LZMA para commons-compress (SevenZFile). Es opcional en su
    // POM y sin él la extracción 7z revienta en runtime (NoClassDefFoundError).
    implementation("org.tukaani:xz:1.9")

    // Carátulas: iconUrl del repo + miniaturas de YouTube, con caché en disco
    // y placeholder si falla (la galería del PC vive de las carátulas).
    implementation("io.coil-kt:coil:2.6.0")
}

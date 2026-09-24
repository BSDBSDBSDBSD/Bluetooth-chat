plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.nearchat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.nearchat"
        minSdk = 33
        targetSdk = 35
        // Grows with every CI build so each new APK is accepted as an update.
        versionCode = 100 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)
        versionName = "1.3"
    }

    // Every build is signed with the same key so a new APK installs over the old one
    // (keeping chats). The keystore in the repo is useless without its password, which
    // lives only in the NEARCHAT_KEYSTORE_PASSWORD secret / environment variable.
    val keystorePassword = System.getenv("NEARCHAT_KEYSTORE_PASSWORD").orEmpty()
    val stableKey = if (keystorePassword.isNotEmpty()) signingConfigs.create("stable") {
        storeFile = rootProject.file("keystore/nearchat.p12")
        storeType = "PKCS12"
        storePassword = keystorePassword
        keyAlias = "nearchat"
        keyPassword = keystorePassword
    } else {
        logger.warn("NEARCHAT_KEYSTORE_PASSWORD not set: signing with a throwaway debug key (updates will not install over it)")
        null
    }

    buildTypes {
        debug {
            if (stableKey != null) signingConfig = stableKey
        }
        release {
            isMinifyEnabled = false
            signingConfig = stableKey ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
}

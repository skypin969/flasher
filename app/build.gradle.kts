plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.skypin.flasher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.skypin.flasher"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "9.6.9"
    }

    // Kunci rilis dibaca dari GitHub Secrets lewat environment variable.
    // Kalau variabelnya tidak ada (misalnya build di komputer), build rilis tidak ditandatangani.
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("KEYSTORE_PATH")
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (System.getenv("KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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

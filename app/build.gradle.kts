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
        versionCode = 2
        versionName = "9.6.9"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

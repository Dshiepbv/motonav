plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "vn.hiep.motonav"
    compileSdk = 34

    defaultConfig {
        applicationId = "vn.hiep.motonav"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }

    // Khoá ký cố định: các bản build sau cài đè lên bản cũ được, không phải gỡ app.
    signingConfigs {
        create("shared") {
            storeFile = file("motonav.keystore")
            storePassword = "motonav123"
            keyAlias = "motonav"
            keyPassword = "motonav123"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

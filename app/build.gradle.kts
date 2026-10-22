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
        versionCode = 15
        versionName = "2.9"
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

dependencies {
    // Bản đồ (hiển thị kiểu bản đồ VietMap)
    implementation("org.maplibre.gl:android-sdk:11.5.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

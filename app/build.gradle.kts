import java.io.FileInputStream
import java.security.KeyStore
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signingStoreFile = file("motonav.keystore")
val expectedSigningCertSha256 = runCatching {
    val keyStore = KeyStore.getInstance("PKCS12")
    FileInputStream(signingStoreFile).use { keyStore.load(it, "motonav123".toCharArray()) }
    val cert = keyStore.getCertificate("motonav") ?: error("Không tìm thấy chứng thư ký MotoNav")
    MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        .joinToString("") { "%02X".format(it) }
}.getOrElse { throw GradleException("Không thể đọc chứng thư ký MotoNav: ${it.message}", it) }

android {
    namespace = "vn.hiep.motonav"
    compileSdk = 34

    defaultConfig {
        applicationId = "vn.hiep.motonav"
        minSdk = 26
        targetSdk = 34
        versionCode = 341
        versionName = "7.28"
        buildConfigField("String", "EXPECTED_SIGNING_CERT_SHA256", "\"$expectedSigningCertSha256\"")
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

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
}

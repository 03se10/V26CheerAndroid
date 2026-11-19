plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.v26cheer"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.v26cheer"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
    // 한글 OCR (모델이 앱에 포함되는 온디바이스 버전)
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
}

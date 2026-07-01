plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.similarscandemo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.similarscandemo"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":similar-scan-core"))
    implementation(project(":video-compress-core"))
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.exoplayer:exoplayer-core:2.19.1")
    implementation("com.google.android.exoplayer:exoplayer-ui:2.19.1")
}

kotlin {
    jvmToolchain(17)
}

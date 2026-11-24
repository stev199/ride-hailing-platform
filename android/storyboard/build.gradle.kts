plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Part E2 Step Storyboard helper as a library (P2-3). Main sources so both app modules'
// androidTest configurations can depend on it (api deps propagate into their test APKs).
// Wired from tools/step-storyboard/StepStoryboard.kt; this module copy is authoritative
// for app wiring.

android {
    namespace = "com.ridehailing.test.storyboard"
    compileSdk = 34

    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api("androidx.test:monitor:1.6.1")
    api("androidx.test.uiautomator:uiautomator:2.3.0")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("junit:junit:4.13.2")
}

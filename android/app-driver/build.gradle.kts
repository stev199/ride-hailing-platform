plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.ridehailing.driver"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ridehailing.driver"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // run-ui-tests.sh passes -Pandroid.testInstrumentationRunnerArguments.<k>=<v>
        // (P2-3: real numShards/shardIndex sharding + runId for the storyboard layout;
        // round-18: "class" so the UI smoke cell can pin the launch suite while the
        // driver flow e2e owns its dedicated cell).
        listOf("numShards", "shardIndex", "runId", "viewerUrl", "class").forEach { key ->
            (project.findProperty("android.testInstrumentationRunnerArguments.$key") as? String)
                ?.let { testInstrumentationRunnerArguments[key] = it }
        }
        // P2-2: self-hosted tiles only. Emulator default reaches the compose geo profile
        // (10.0.2.2 = host loopback); CI/dev override with -Pmap.styleUrl=<url>.
        buildConfigField(
            "String",
            "API_BASE_URL",
            "\"${project.findProperty("api.baseUrl") as? String ?: "http://10.0.2.2:8080"}\"",
        )
        buildConfigField(
            "String",
            "MAP_STYLE_URL",
            // Round-13: default MUST match MapConfig.EMULATOR_STYLE_URL ("basic-preview" is
            // the only style tileserver-gl v4.6.2 bundles). The stale /styles/basic default
            // made every non-overridden build (android-ui shards, e2e-offline, nightly)
            // target a 404-forever style -> offline download Failed + graceful-offline UI.
            "\"${project.findProperty("map.styleUrl") as? String ?: "http://10.0.2.2:8081/styles/basic-preview/style.json"}\"",
        )
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation(project(":core-ui"))
    implementation(project(":core-data"))
    implementation(project(":core-map"))
    implementation(project(":core-screens"))

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-compiler:2.52")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation(project(":storyboard"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

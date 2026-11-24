plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}
// Isolated emulator-only UI capture build; normal APKs target ARM64 exclusively.
val uiCapture = providers.gradleProperty("uiCapture").map(String::toBoolean).getOrElse(false)
if (uiCapture) {
    gradle.taskGraph.whenReady {
        require(allTasks.none { it.name.contains("Release", ignoreCase = true) }) {
            "uiCapture is only available for debug emulator screenshots."
        }
    }
}
android {
    namespace = "dev.pathpilot.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "dev.pathpilot.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += if (uiCapture) "x86_64" else "arm64-v8a" }
    }
    buildTypes {
        debug {
            if (uiCapture) applicationIdSuffix = ".capture"
            buildConfigField("boolean", "ALLOW_SCREENSHOTS", uiCapture.toString())
        }
        release { buildConfigField("boolean", "ALLOW_SCREENSHOTS", "false") }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("plugin.serialization")
}
android {
    namespace = "io.github.jerryskywalker.tmcomp.core"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    api("com.google.android.gms:play-services-wearable:20.0.1")
    api("androidx.work:work-runtime-ktx:2.10.5")
    testImplementation("junit:junit:4.13.2")
}

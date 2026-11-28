import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.noyclic.literdsp"
    compileSdk = 36
    ndkVersion = "27.2.12479018"
    buildFeatures { prefab = true }
    defaultConfig {
        applicationId = "com.noyclic.literdsp"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources { excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") } }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
dependencies {
    // Isolated legacy Interpreter/QNN stack; never add these to :app (Play-services LiteRT 17).
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("com.qualcomm.qti:qnn-runtime:2.34.0")
    implementation("com.qualcomm.qti:qnn-litert-delegate:2.34.0")
}

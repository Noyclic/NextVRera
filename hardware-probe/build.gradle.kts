import java.net.URL
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.noyclic.hardwareprobe"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.noyclic.nextvrera.hardwareprobe"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.4.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
    // GPU delegate Java API classes are published in this split artifact.
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.16.1")

    implementation("com.qualcomm.qti:qnn-runtime:2.34.0")
    implementation("com.qualcomm.qti:qnn-litert-delegate:2.34.0")

    testImplementation("junit:junit:4.13.2")
}

val probeModel = layout.projectDirectory.file("src/main/assets/mobilenet_v1_1.0_224_quant.tflite")
val downloadProbeModel by tasks.registering {
    outputs.file(probeModel)
    doLast {
        val destination = probeModel.asFile
        if (!destination.exists() || destination.length() < 1_000_000L) {
            destination.parentFile.mkdirs()
            val temporary = destination.resolveSibling(destination.name + ".part")
            try {
                URL("https://raw.githubusercontent.com/tflite-soc/tensorflow-models/master/mobilenet-v1/mobilenet_v1_1.0_224_quant.tflite")
                    .openConnection()
                    .apply {
                        connectTimeout = 20_000
                        readTimeout = 90_000
                    }
                    .getInputStream()
                    .use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }
                check(temporary.length() > 1_000_000L) { "Downloaded MobileNet model is unexpectedly small." }
                if (destination.exists()) destination.delete()
                check(temporary.renameTo(destination)) { "Could not install probe model asset." }
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(downloadProbeModel) }

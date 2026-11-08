plugins {
    alias(libs.plugins.android.library)
}

// Same ABI switch as :app (pass -PllamaAbis=arm64-v8a,x86_64 for the emulator)
val llamaAbis = (findProperty("llamaAbis") as String? ?: "arm64-v8a").split(",").map { it.trim() }

// Text-to-image with stable-diffusion.cpp, in its own native project: it bundles a patched ggml
// that must not meet llama.cpp's (see src/main/cpp/CMakeLists.txt).
android {
    namespace = "io.github.harryl0l.imagegen"
    compileSdk {
        version = release(37)
    }
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += llamaAbis }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

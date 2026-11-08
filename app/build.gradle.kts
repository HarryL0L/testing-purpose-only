plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Pass -PllamaAbis=arm64-v8a,x86_64 to also build for the emulator.
val llamaAbis = (findProperty("llamaAbis") as String? ?: "arm64-v8a").split(",").map { it.trim() }

android {
    namespace = "io.github.harryl0l.llamachat"
    compileSdk {
        version = release(37)
    }
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "io.github.harryl0l.llamachat"
        minSdk = 31 // Android 12: Material You dynamic color
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0-b12111"

        ndk { abiFilters += llamaAbis }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DBUILD_SHARED_LIBS=ON",
                    "-DLLAMA_BUILD_APP=OFF",
                    "-DLLAMA_BUILD_COMMON=ON",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_BUILD_MTMD=ON",   // multimodal (vision) library only
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_OPENSSL=OFF",
                    "-DMTMD_VIDEO=OFF",       // video frames are decoded by Android, not ffmpeg
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_BACKEND_DL=ON",
                    "-DGGML_CPU_ALL_VARIANTS=ON",
                    "-DGGML_LLAMAFILE=OFF",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    // "standard": CPU only. "experimental": + llama.cpp Vulkan backend and libadrenotools, so Adreno
    // GPUs can run with a custom Vulkan driver (e.g. Mesa Turnip). arm64 only, installs side by side.
    flavorDimensions += "gpu"
    productFlavors {
        create("standard") {
            dimension = "gpu"
            isDefault = true
            buildConfigField("boolean", "GPU_EXPERIMENTAL", "false")
        }
        create("experimental") {
            dimension = "gpu"
            applicationIdSuffix = ".experimental"
            versionNameSuffix = "-vulkan-experimental"
            buildConfigField("boolean", "GPU_EXPERIMENTAL", "true")
            externalNativeBuild { cmake { arguments += listOf("-DLLAMACHAT_VULKAN=ON", "-DLLAMACHAT_MALI=ON") } }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so the release APK installs out of the box.
            // Replace with your own signingConfig before distributing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // ggml loads its CPU backend variants from the native lib dir at runtime,
        // so the .so files must be extracted to disk.
        jniLibs.useLegacyPackaging = true
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}

dependencies {
    implementation(project(":imagegen"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}

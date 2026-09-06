plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.anywhere.transcript"
    compileSdk = 37
    ndkVersion = "30.0.16138531"

    defaultConfig {
        applicationId = "com.anywhere.transcript"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // NOTE: no default abiFilters here — see buildTypes. The arm64-v8a
        // libwhisperjni.so comes from jniLibs (prebuilt with the Hexagon SDK,
        // see hexagon-npu-libs/HEXAGON_INTEGRATION.md); the native build below
        // must not produce its own arm64 lib or it would shadow the NPU one.

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DGGML_OPENCL=ON",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_BACKEND_DL=OFF",
                    "-DWHISPER_BUILD_TESTS=OFF",
                    "-DWHISPER_BUILD_EXAMPLES=OFF",
                    "-DWHISPER_BUILD_SERVER=OFF",
                    "-DWHISPER_CURL=OFF",
                    "-DWH_ENABLE_HEXAGON=OFF",
                    "-DGGML_HEXAGON=OFF",
                )
            }
        }
    }

    buildTypes {
        debug {
            ndk {
                abiFilters += "x86_64"   // emulator: real CPU+OpenCL build
                abiFilters += "arm64-v8a" // device: packaged from jniLibs (CMake just copies)
            }
        }
        release {
            ndk { abiFilters += "arm64-v8a" }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../whisper-jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // Extract .so files to disk: the DSP loader must read the hexagon skel
        // (libggml-htp-vXX.so) as a real file via ADSP_LIBRARY_PATH.
        jniLibs { useLegacyPackaging = true }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)

    // v2 QNN engine: ORT with QNN EP + the Qualcomm HTP runtime (libQnnHtp.so + stub/skel libs)
    implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.27.0")
    implementation("com.qualcomm.qti:qnn-runtime:2.48.0")

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

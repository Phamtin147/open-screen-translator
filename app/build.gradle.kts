plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.openscreentranslator.app"
    compileSdk = 34
    ndkVersion = "28.2.13676358"

    val appVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 100
    val appVersionName = (project.findProperty("versionName") as String?)?.takeIf { it.isNotBlank() } ?: "1.1.0"

    val ksPath = System.getenv("ANDROID_KEYSTORE_PATH")
    val ksStorePass = System.getenv("ANDROID_KEYSTORE_PASSWORD")
    val ksAlias = System.getenv("ANDROID_KEY_ALIAS")
    val ksKeyPass = System.getenv("ANDROID_KEY_PASSWORD")
    val hasReleaseSigning = !ksPath.isNullOrBlank() &&
        file(ksPath).exists() &&
        !ksStorePass.isNullOrBlank() &&
        !ksAlias.isNullOrBlank() &&
        !ksKeyPass.isNullOrBlank()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = ksStorePass
                keyAlias = ksAlias
                keyPassword = ksKeyPass
            }
        }
    }

    defaultConfig {
        applicationId = "com.openscreentranslator.app"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DBUILD_SHARED_LIBS=ON"
                arguments += "-DLLAMA_BUILD_APP=OFF"
                arguments += "-DLLAMA_BUILD_COMMON=ON"
                arguments += "-DLLAMA_OPENSSL=OFF"
                arguments += "-DLLAMA_BUILD_TESTS=OFF"
                arguments += "-DLLAMA_BUILD_TOOLS=OFF"
                arguments += "-DLLAMA_BUILD_EXAMPLES=OFF"
                arguments += "-DLLAMA_BUILD_SERVER=OFF"
                arguments += "-DGGML_NATIVE=OFF"
                arguments += "-DGGML_BACKEND_DL=ON"
                arguments += "-DGGML_CPU_ALL_VARIANTS=ON"
                arguments += "-DGGML_LLAMAFILE=OFF"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ML Kit Language Identification
    implementation("com.google.mlkit:language-id:17.0.4")
    
    // Google Play Services Base (needed for Tasks)
    implementation("com.google.android.gms:play-services-base:18.3.0")

    // ML Kit Text Recognition (OCR) Bundled Models (100% offline instant on first tap, no GMS hang)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    
    // ML Kit On-Device Translation
    implementation("com.google.mlkit:translate:17.0.2")

    testImplementation("junit:junit:4.13.2")
}

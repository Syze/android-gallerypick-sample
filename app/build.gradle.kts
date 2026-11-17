plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "co.getswan.gallerysample"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "co.getswan.gallerysample"
        // Android 10: MediaStore.loadThumbnail and scoped storage, which the scan will need.
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // Phones are arm64; OpenCV and MediaPipe ship native code per ABI, so one ABI keeps the APK small.
        ndk { abiFilters += "arm64-v8a" }
    }

    // Sandbox and production, like the iOS sample's two schemes.
    flavorDimensions += "env"
    productFlavors {
        // Default, so a fresh checkout runs against the dev services like the iOS Sandbox scheme.
        create("sandbox") { dimension = "env"; applicationIdSuffix = ".sandbox"; versionNameSuffix = "-sandbox"; isDefault = true }
        create("production") { dimension = "env" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { buildConfig = true }
    // Model files are read by path or memory-mapped: keep them uncompressed in the APK.
    androidResources { noCompress += listOf("task", "tflite", "onnx") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.webkit)
    implementation(libs.okhttp)
    implementation(libs.androidx.security.crypto)
    // The scan: MediaPipe for faces, bodies and segmentation; OpenCV runs the SFace model.
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.opencv)
    // Face boxes at every distance (the landmarker alone misses far faces); the model lives in Play services.
    implementation(libs.mlkit.face.detection)
    implementation(libs.androidx.exifinterface)   // ISO, focal length and exposure of a selfie
    implementation(libs.mlkit.text.recognition)    // printed text around a face: the document gate
    implementation(libs.mlkit.image.labeling)      // glasses, hats, suits, gowns: the label rules
}

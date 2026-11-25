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
        create("sandbox") { dimension = "env"; applicationIdSuffix = ".sandbox"; versionNameSuffix = "-sandbox"; isDefault = true
            // sandboxRelease installs like the debug build (debug key), so testers time the real app without the debug extras.
            signingConfig = signingConfigs.getByName("debug") }
        create("production") { dimension = "env" }
    }

    buildTypes {
        release {
            // R8 drops unused code and resources; keep rules for the libraries in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { buildConfig = true }
    // MediaPipe and the OpenCV jniLibs copy both carry libc++_shared.so (the same NDK runtime); one is packed.
    packaging { jniLibs { pickFirsts += "lib/arm64-v8a/libc++_shared.so" } }
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
    // OpenCV: the official package's Java classes, with a slim build of its native library (core, imgproc, dnn, objdetect
    // only; same version, compiler, flags and HALs as the official one, so the face match reads the same) in
    // src/main/jniLibs. To go back to the official package, use `implementation(libs.opencv)` and delete the jniLibs copy.
    implementation(files("libs/opencv-4.14.0-classes.jar"))
    // Face boxes at every distance (the landmarker alone misses far faces); the model lives in Play services.
    implementation(libs.mlkit.face.detection)
    implementation(libs.androidx.exifinterface)   // ISO, focal length and exposure of a selfie
    // Text comes from Play services like the face model (12.5 MB smaller, same reads). Labels stay bundled: the Play
    // services label model reads differently and would move the glasses, hat and scarf rules.
    implementation(libs.mlkit.text.recognition)    // printed text around a face: the document gate
    implementation(libs.mlkit.image.labeling)      // glasses, hats, suits, gowns: the label rules
}

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.posebenchmark"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.posebenchmark"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    val cameraXVersion = "1.6.2"

    implementation("androidx.activity:activity-ktx:1.11.0")

    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")
    implementation("com.google.android.material:material:1.12.0")

    //MEDIAPIPE
    implementation("com.google.mediapipe:tasks-vision:1.0.0")
}

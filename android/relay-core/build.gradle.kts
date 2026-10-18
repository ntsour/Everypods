// Wi-Fi audio relay foundations: packet format, AEAD session, control framing (host-tested
// Kotlin) and the libopus JNI codec (instrumented test). Debug-only consumer for now.
plugins { alias(libs.plugins.android.library) }

android {
    namespace = "io.automated.ventures.everypods.relay"
    compileSdk = 37
    ndkVersion = "30.0.14904198"
    defaultConfig {
        minSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=none") } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

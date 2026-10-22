plugins {
    id("com.android.library")
}

android {
    namespace = "io.automated.ventures.everypods.opus"
    compileSdk = 37

    buildTypes {
        create("relayPreview") {
            matchingFallbacks += listOf("debug")
        }
    }

    defaultConfig {
        minSdk = 33
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fvisibility=hidden")
                arguments += listOf(
                    "-DOPUS_BUILD_TESTING=OFF",
                    "-DOPUS_BUILD_PROGRAMS=OFF",
                    "-DOPUS_BUILD_SHARED_LIBRARY=OFF",
                    "-DOPUS_ENABLE_FLOAT_API=ON",
                    "-DOPUS_DRED=OFF",
                    "-DOPUS_OSCE=OFF",
                    "-DOPUS_INSTALL_PKG_CONFIG_MODULE=OFF",
                    "-DOPUS_INSTALL_CMAKE_CONFIG_MODULE=OFF",
                )
            }
        }
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "30.0.14904198"
}

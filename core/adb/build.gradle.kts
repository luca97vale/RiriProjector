plugins {
    alias(libs.plugins.riri.android.library)
}

// Wireless-debugging pairing and ADB client, derived from Shizuku (Apache-2.0, ADR-014).
android {
    namespace = "dev.ririprojector.core.adb"
    ndkVersion = libs.versions.ndk.get()
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=none")
            }
        }
    }
    buildFeatures {
        prefab = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.boringssl)
    implementation(libs.libcxx)
    implementation(libs.hiddenapibypass)
    implementation(libs.bouncycastle.pkix)
}

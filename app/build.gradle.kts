plugins {
    alias(libs.plugins.riri.android.application)
}

android {
    namespace = "dev.ririprojector"

    defaultConfig {
        applicationId = "dev.ririprojector"
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":feature:sender"))
    implementation(project(":feature:receiver"))
    implementation(libs.androidx.core.ktx)
}

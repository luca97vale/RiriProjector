plugins {
    alias(libs.plugins.riri.android.library)
}

// Shell-uid helper for the second-screen mode (ADR-014): runs in app_process, talks over a binder.
android {
    namespace = "dev.ririprojector.core.helper"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures {
        aidl = true
    }
}

dependencies {
    api(project(":core:adb"))
}

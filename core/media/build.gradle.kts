plugins {
    alias(libs.plugins.riri.android.library)
}

android {
    namespace = "dev.ririprojector.core.media"
}

dependencies {
    api(project(":core:protocol"))
    implementation(libs.kotlinx.coroutines.android)
}

plugins {
    alias(libs.plugins.riri.android.library)
}

android {
    namespace = "dev.ririprojector.core.net"
}

dependencies {
    api(project(":core:protocol"))
    implementation(libs.kotlinx.coroutines.android)
}

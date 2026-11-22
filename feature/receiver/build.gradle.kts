plugins {
    alias(libs.plugins.riri.android.library)
}

android {
    namespace = "dev.ririprojector.feature.receiver"
}

dependencies {
    implementation(project(":core:net"))
    implementation(project(":core:media"))
    implementation(project(":core:ui"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
}

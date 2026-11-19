plugins {
    alias(libs.plugins.riri.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.ririprojector.feature.sender"
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:net"))
    implementation(project(":core:media"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}

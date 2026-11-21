plugins {
    alias(libs.plugins.riri.android.library)
}

// Shared look of the sender and receiver screens: brand colors (from the launcher artwork) and
// the animated dog loader. View-based, so the Compose sender and the View-based receiver both use it.
android {
    namespace = "dev.ririprojector.core.ui"
}

dependencies {
    implementation(libs.androidx.core.ktx)
}

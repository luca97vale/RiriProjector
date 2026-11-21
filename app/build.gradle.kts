plugins {
    alias(libs.plugins.riri.android.application)
}

/** Environment variable first, then a Gradle property (e.g. in the user-level ~/.gradle/gradle.properties). */
fun setting(env: String, property: String): String? =
    providers.environmentVariable(env).orElse(providers.gradleProperty(property)).orNull?.takeIf { it.isNotBlank() }

// Release signing (ADR-012): all four values or none. None → unsigned release build, so CI and
// contributors need no secrets. The keystore lives outside the repo; the path must be absolute.
val releaseSigning =
    listOf(
        setting("RIRI_KEYSTORE_FILE", "riri.keystore.file"),
        setting("RIRI_KEYSTORE_PASSWORD", "riri.keystore.password"),
        setting("RIRI_KEY_ALIAS", "riri.key.alias"),
        setting("RIRI_KEY_PASSWORD", "riri.key.password"),
    )
val signingValuesSet = releaseSigning.count { it != null }
check(signingValuesSet == 0 || signingValuesSet == releaseSigning.size) {
    "Release signing: set all four of RIRI_KEYSTORE_FILE, RIRI_KEYSTORE_PASSWORD, RIRI_KEY_ALIAS, " +
        "RIRI_KEY_PASSWORD (or the riri.* Gradle properties), or none of them. $signingValuesSet set."
}

android {
    namespace = "dev.ririprojector"

    defaultConfig {
        applicationId = "dev.ririprojector"
        // The release workflow derives both from the tag: vX.Y.Z → X*10000 + Y*100 + Z.
        versionCode = providers.gradleProperty("riri.versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("riri.versionName").orNull ?: "0.1.0"
    }

    if (signingValuesSet == releaseSigning.size) {
        val (storePath, storePass, alias, keyPass) = releaseSigning.map { it!! }
        signingConfigs.create("release") {
            storeFile = file(storePath)
            storePassword = storePass
            keyAlias = alias
            keyPassword = keyPass
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
}

dependencies {
    implementation(project(":feature:sender"))
    implementation(project(":feature:receiver"))
    implementation(libs.androidx.core.ktx)
}

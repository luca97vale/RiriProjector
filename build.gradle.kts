plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.spotless)
}

spotless {
    val ktlintVersion = libs.versions.ktlint.get()
    val ktlintRules =
        mapOf(
            "ktlint_code_style" to "android_studio",
            "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
        )
    kotlin {
        // Module folders only: `.claude/worktrees` holds other checkouts with their own builds.
        target("app/**/*.kt", "core/**/*.kt", "feature/**/*.kt", "build-logic/**/*.kt")
        targetExclude("**/build/**")
        ktlint(ktlintVersion).editorConfigOverride(ktlintRules)
    }
    kotlinGradle {
        target(
            "*.gradle.kts",
            "app/**/*.gradle.kts",
            "core/**/*.gradle.kts",
            "feature/**/*.gradle.kts",
            "build-logic/**/*.gradle.kts",
        )
        targetExclude("**/build/**")
        ktlint(ktlintVersion).editorConfigOverride(ktlintRules)
    }
}

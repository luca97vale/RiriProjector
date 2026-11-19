import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String = findVersion(alias).get().requiredVersion

/** Shared Android configuration for application and library modules. */
internal fun Project.configureAndroidCommon(extension: CommonExtension) {
    extension.apply {
        compileSdk = libs.version("compileSdk").toInt()
        defaultConfig.apply {
            minSdk = libs.version("minSdk").toInt()
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        compileOptions.apply {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        lint.apply {
            warningsAsErrors = false
            abortOnError = true
            checkDependencies = false
            // Version-freshness checks belong to Dependabot, not to CI failures.
            disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
        }
        testOptions.unitTests.isReturnDefaultValues = true
    }
    configureKotlinJvmTarget()
    addCommonTestDependencies()
}

internal fun Project.addCommonTestDependencies() {
    dependencies {
        add("testImplementation", libs.findLibrary("junit").get())
        add("testImplementation", libs.findLibrary("truth").get())
        add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
    }
}

import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/** Pure Kotlin/JVM module (no Android dependencies), e.g. `:core:protocol`. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            pluginManager.apply("java-library")
            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            configureKotlinJvmTarget()
            // Compile against the JDK 17 API even though Gradle runs on JDK 25, so pure modules
            // never use APIs that are missing on Android.
            tasks.withType(JavaCompile::class.java).configureEach { options.release.set(17) }
            tasks.withType(KotlinJvmCompile::class.java).configureEach {
                compilerOptions.freeCompilerArgs.add("-Xjdk-release=17")
            }
            addCommonTestDependencies()
        }
    }
}

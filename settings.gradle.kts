pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "RiriProjector"

include(":app")
include(":core:protocol")
include(":core:net")
include(":core:media")
include(":core:ui")
include(":core:adb")
include(":core:helper")
include(":feature:sender")
include(":feature:receiver")

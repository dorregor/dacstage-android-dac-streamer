pluginManagement {
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
        // Solo para libadb-android (cliente ADB local del host, opcional para la cola de UAPP).
        maven("https://jitpack.io") {
            content { includeGroupByRegex("""com.github.MuntashirAkon.*""") }
        }
    }
}
rootProject.name = "dacstage"
include(":app")
include(":baselineprofile")

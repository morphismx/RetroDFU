pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
    }
}

includeBuild("upstream")

rootProject.name = "RetroDFU"

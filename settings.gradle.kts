pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenLocal()
        mavenCentral()
    }
}

rootProject.name = "android-pos"
include(":app")

// Repos hermanos del laboratorio: si están clonados al lado, Gradle los compila
// desde el código (composite build) y no hace falta publicar nada.
listOf("dukpt-kt", "iso8583-kt", "pinpad-protocol-kt").forEach { sibling ->
    if (file("../$sibling").exists()) includeBuild("../$sibling")
}

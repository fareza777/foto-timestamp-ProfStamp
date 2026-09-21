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
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Official C2PA Android SDK (contentauth/c2pa-android) is published via JitPack, not Maven Central.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "ProofStamp"
include(":app")

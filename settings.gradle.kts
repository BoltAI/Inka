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
        maven("https://jitpack.io")
        maven("http://repo.boox.com/repository/maven-public/") {
            isAllowInsecureProtocol = true
        }
    }
}

rootProject.name = "Inka"
include(":app")

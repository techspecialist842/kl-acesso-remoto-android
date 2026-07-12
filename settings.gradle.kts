pluginManagement {
    repositories {
        google()          // Repositório oficial do Android
        mavenCentral()    // Repositório de bibliotecas do Java/Kotlin
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

rootProject.name = "AcessoRemotoApp"
include(":app")
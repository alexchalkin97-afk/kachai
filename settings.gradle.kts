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
        // RuStore Pay SDK (оплата подписки)
        maven("https://nexus-external.rustore.ru/repository/maven-rustore-exposed")
    }
}

rootProject.name = "Kachai"
include(":app")

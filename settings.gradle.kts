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
    }
}
rootProject.name = "TRACC"
include(":app")
// Общая логика для Android и iOS: парсеры, сборка конфигурации Xray, заголовки провайдера, маршрутизация.
include(":shared")
// Генератор Baseline Profile: ./gradlew :app:generateReleaseBaselineProfile (нужен эмулятор или телефон).
include(":baselineprofile")

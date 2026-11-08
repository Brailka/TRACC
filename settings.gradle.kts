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
rootProject.name = "RVC"
include(":app")
// Общая логика для Android и iOS: парсеры, сборка конфигурации Xray, заголовки провайдера, маршрутизация.
include(":shared")
// Генератор Baseline Profile: ./gradlew :app:generateReleaseBaselineProfile (нужен эмулятор или телефон).
include(":baselineprofile")
// Windows-версия: Compose Desktop, ядро rvc-core.exe (Go + Xray) и rvcnet.dll. См. desktop/README.md.
include(":desktop")

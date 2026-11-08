import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Генерирует Baseline Profile для приложения: проходит запуск, прокрутку главного экрана и настройки,
 * а Android заранее компилирует этот код при установке — быстрее первый запуск и без рывков на первой прокрутке.
 *
 *   ./gradlew :app:generateReleaseBaselineProfile     # эмулятор или телефон с Android 9+ подключён через adb
 *
 * Результат кладётся в app/src/release/generated/baselineProfiles и попадает в APK.
 */
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "app.rvc.baselineprofile"
    compileSdk = 36
    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    targetProjectPath = ":app"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.junit.ext)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro)
}

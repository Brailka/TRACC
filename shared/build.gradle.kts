import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

/**
 * Общий модуль RVC для Android и iOS (Kotlin Multiplatform): ссылки и подписки, заголовки провайдера,
 * профили маршрутизации и сборка конфигурации Xray. Платформенные мелочи (AES-GCM, SHA-1, zlib/gzip,
 * графемы) — в Platform.kt через expect/actual.
 *
 * Windows: цель jvm("desktop") для модуля :desktop (Compose Desktop); actual — тот же JVM-код, что на Android.
 *
 * iOS: статический фреймворк RvcShared для Xcode. Собирается только на macOS
 * (./gradlew :shared:linkReleaseFrameworkIosArm64); на Windows iOS-цели пропускаются.
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    // ./gradlew :shared:assembleRvcSharedXCFramework (только на macOS) — RvcShared.xcframework для Xcode.
    val xcf = XCFramework("RvcShared")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "RvcShared"
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        all { languageSettings.optIn("kotlin.io.encoding.ExperimentalEncodingApi") }
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "app.rvc.shared"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

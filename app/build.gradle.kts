import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
}

fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

val signingProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "app.tracc"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.tracc"
        minSdk = 26
        targetSdk = 35
        val tagVersion = (findProperty("traccVersion") as String?)?.removePrefix("v")
        versionName = tagVersion ?: "1.0.0"
        versionCode = tagVersion?.split('.', '-')?.let { p -> p[0].toInt() * 10000 + p[1].toInt() * 100 + p[2].toInt() } ?: 10000
        buildConfigField("String", "UPDATE_REPO", "\"${findProperty("traccUpdateRepo") ?: "OWNER/TRACC"}\"")
    }

    signingConfigs {
        create("release") {
            val keystore = env("TRACC_KEYSTORE") ?: signingProps.getProperty("storeFile")
            if (keystore != null) {
                storeFile = file(keystore)
                val password = env("TRACC_KEYSTORE_PASSWORD") ?: signingProps.getProperty("storePassword")
                storePassword = password
                keyAlias = env("TRACC_KEY_ALIAS") ?: signingProps.getProperty("keyAlias") ?: "tracc"
                keyPassword = env("TRACC_KEY_PASSWORD") ?: signingProps.getProperty("keyPassword") ?: password
            }
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.getByName("release").takeIf { it.storeFile != null }?.let { signingConfig = it }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // Полные базы из libv2ray.aar заменены урезанными из app/src/main/assets.
        ignoreAssetsPattern = "!geoip-only-cn-private.dat:!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~"
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources.excludes += listOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "DebugProbesKt.bin", "kotlin-tooling-metadata.json")
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime)
    // Ставит Baseline Profile при установке из APK (не только из Google Play).
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}

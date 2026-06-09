@file:Suppress("UnusedPrivateProperty")

import com.android.build.api.variant.ResValue
import org.jetbrains.kotlin.konan.properties.Properties

plugins {
    id("matsumo.primitive.android.application")
    id("matsumo.primitive.detekt")
}

val localProperties = Properties().apply {
    project.rootDir.resolve("local.properties").also {
        if (it.exists()) load(it.inputStream())
    }
}

/** AdMob のテスト用アプリ ID。 */
val admobTestAppId = "ca-app-pub-0000000000000000~0000000000"

/** AdMob のテスト用バナー広告ユニット ID。 */
val bannerAdTestId = "ca-app-pub-3940256099942544/6300978111"

/** AdMob のテスト用ネイティブ広告ユニット ID。 */
val nativeAdTestId = "ca-app-pub-3940256099942544/2247696110"

/** AdMob のテスト用リワード広告ユニット ID。 */
val rewardAdTestId = "ca-app-pub-3940256099942544/5224354917"

android {
    namespace = "me.matsumo.romaflow"

    signingConfigs {
        getByName("debug") {
            storeFile = file("${project.rootDir}/gradle/keystore/debug.keystore")
        }
        create("release") {
            storeFile = file("${project.rootDir}/gradle/keystore/release.keystore")
            storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD") ?: System.getenv("RELEASE_STORE_PASSWORD")
            keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD") ?: System.getenv("RELEASE_KEY_PASSWORD")
            keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS") ?: System.getenv("RELEASE_KEY_ALIAS")
        }
        create("billing") {
            storeFile = file("${project.rootDir}/gradle/keystore/release.keystore")
            storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD") ?: System.getenv("RELEASE_STORE_PASSWORD")
            keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD") ?: System.getenv("RELEASE_KEY_PASSWORD")
            keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS") ?: System.getenv("RELEASE_KEY_ALIAS")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = true
            versionNameSuffix = ".D"
            applicationIdSuffix = ".debug"
        }
        create("billing") {
            signingConfig = signingConfigs.getByName("billing")
            isDebuggable = true
            matchingFallbacks.add("debug")
        }
    }

    androidComponents {
        onVariants {
            val appName = when (it.buildType) {
                "debug" -> "RomaFlow Debug"
                "billing" -> "RomaFlow Billing"
                else -> null
            }

            it.manifestPlaceholders.apply {
                put("ADMOB_ANDROID_APP_ID", localProperties.getProperty("ADMOB_ANDROID_APP_ID") ?: admobTestAppId)
                put("ADMOB_IOS_APP_ID", localProperties.getProperty("ADMOB_IOS_APP_ID") ?: admobTestAppId)
            }

            if (appName != null) {
                it.resValues.apply {
                    put(it.makeResValueKey("string", "app_name"), ResValue(appName, null))
                }
            }

            if (it.buildType == "release") {
                it.packaging.resources.excludes.add("META-INF/**")
            }
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":core:model"))
    implementation(project(":core:repository"))
    implementation(project(":core:ui"))

    implementation(libs.bundles.ui.android)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.kmp.lifecycle.runtime.compose)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.startup)
    implementation(libs.filekit.dialogs)
    implementation(libs.napier)
    implementation(libs.play.review)
    implementation(libs.play.update)
    implementation(libs.play.service.ads)
}

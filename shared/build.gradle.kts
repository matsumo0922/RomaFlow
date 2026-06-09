@file:Suppress("UnusedPrivateProperty")

import com.codingfeline.buildkonfig.compiler.FieldSpec
import org.jetbrains.kotlin.konan.properties.Properties

plugins {
    id("matsumo.primitive.kmp.common")
    id("matsumo.primitive.android.library")
    id("matsumo.primitive.kmp.compose")
    id("matsumo.primitive.kmp.android")
    id("matsumo.primitive.kmp.ios")
    id("matsumo.primitive.detekt")
    alias(libs.plugins.build.konfig)
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

kotlin {
    android {
        namespace = "me.matsumo.romaflow.shared"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:common"))
            implementation(project(":core:model"))
            implementation(project(":core:datasource"))
            implementation(project(":core:repository"))
            implementation(project(":core:billing"))
            implementation(project(":core:ui"))
            implementation(project(":core:resource"))

            implementation(project(":feature:home"))
            implementation(project(":feature:setting"))
            implementation(project(":feature:billing"))
        }

        androidMain.dependencies {
            implementation(libs.kmp.lifecycle.viewmodel.compose)
        }
    }
}

buildkonfig {
    packageName = "me.matsumo.romaflow"

    defaultConfigs {
        fun setField(name: String, defaultValue: String = "") {
            val envValue = System.getenv(name)
            val propertyValue = localProperties.getProperty(name)

            buildConfigField(FieldSpec.Type.STRING, name, propertyValue ?: envValue ?: defaultValue)
        }

        setField("VERSION_NAME", libs.versions.versionName.get())
        setField("VERSION_CODE", libs.versions.versionCode.get())

        setField("DEVELOPER_PIN", "1234")
        setField("PURCHASE_ANDROID_API_KEY")
        setField("PURCHASE_IOS_API_KEY")

        setField("ADMOB_ANDROID_APP_ID", admobTestAppId)
        setField("ADMOB_ANDROID_BANNER_AD_UNIT_ID", admobTestAppId)
        setField("ADMOB_ANDROID_INTERSTITIAL_AD_UNIT_ID", bannerAdTestId)
        setField("ADMOB_ANDROID_NATIVE_AD_UNIT_ID", nativeAdTestId)
        setField("ADMOB_ANDROID_REWARDED_AD_UNIT_ID", rewardAdTestId)

        setField("ADMOB_IOS_APP_ID", admobTestAppId)
        setField("ADMOB_IOS_BANNER_AD_UNIT_ID", bannerAdTestId)
        setField("ADMOB_IOS_INTERSTITIAL_AD_UNIT_ID", bannerAdTestId)
        setField("ADMOB_IOS_NATIVE_AD_UNIT_ID", nativeAdTestId)
        setField("ADMOB_IOS_REWARDED_AD_UNIT_ID", rewardAdTestId)

        setField("APPLOVIN_SDK_KEY")
    }
}

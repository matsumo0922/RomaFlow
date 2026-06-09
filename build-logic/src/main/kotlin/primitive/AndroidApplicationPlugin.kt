package primitive

import me.matsumo.romaflow.androidApplication
import me.matsumo.romaflow.libs
import me.matsumo.romaflow.setupAndroid
import me.matsumo.romaflow.version
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Android application module の共通 Gradle 設定を適用するプラグイン。
 */
class AndroidApplicationPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.application")
                apply("kotlin-parcelize")
                apply("kotlinx-serialization")
                apply("org.jetbrains.kotlin.plugin.compose")
                apply("project-report")
                apply("com.google.gms.google-services")
                apply("com.google.firebase.crashlytics")
                apply("com.google.devtools.ksp")
                apply("com.mikepenz.aboutlibraries.plugin")
            }

            androidApplication {
                setupAndroid()

                compileSdk = libs.version("compileSdk").toInt()
                defaultConfig.targetSdk = libs.version("targetSdk").toInt()
                buildFeatures.compose = true
                buildFeatures.buildConfig = true
                buildFeatures.resValues = true
                buildFeatures.viewBinding = true

                defaultConfig {
                    applicationId = "me.matsumo.romaflow"

                    versionName = libs.version("versionName")
                    versionCode = libs.version("versionCode").toInt()
                }

                packaging {
                    resources.excludes.addAll(
                        listOf(
                            "LICENSE",
                            "LICENSE.txt",
                            "NOTICE",
                            "asm-license.txt",
                            "cglib-license.txt",
                            "mozilla/public-suffix-list.txt",
                        ),
                    )
                }
            }
        }
    }
}

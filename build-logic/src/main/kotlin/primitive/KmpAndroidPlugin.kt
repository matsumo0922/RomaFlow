package primitive

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import me.matsumo.romaflow.libs
import me.matsumo.romaflow.version
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Kotlin Multiplatform module の Android target 設定を適用する Gradle プラグイン。
 */
@Suppress("unused")
class KmpAndroidPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
                kotlin {
                    targets.withType(KotlinMultiplatformAndroidLibraryTarget::class.java).configureEach {
                        compileSdk = libs.version("compileSdk").toInt()
                        minSdk = libs.version("minSdk").toInt()

                        androidResources {
                            enable = true
                        }

                        compilerOptions {
                            jvmTarget.set(JvmTarget.JVM_17)
                        }
                    }
                }
            }
        }
    }
}

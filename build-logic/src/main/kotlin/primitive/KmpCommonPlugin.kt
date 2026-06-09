package primitive

import me.matsumo.romaflow.library
import me.matsumo.romaflow.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * 共通 Kotlin Multiplatform 設定を適用する Gradle プラグイン。
 */
class KmpCommonPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("org.jetbrains.kotlin.multiplatform")
            }

            kotlin {
                applyDefaultHierarchyTemplate()

                sourceSets.commonMain.dependencies {
                    val kotlinBom = libs.library("kotlin-bom")
                    implementation(project.dependencies.platform(kotlinBom))
                }
            }
        }
    }
}

fun Project.kotlin(action: KotlinMultiplatformExtension.() -> Unit) {
    extensions.configure(action)
}

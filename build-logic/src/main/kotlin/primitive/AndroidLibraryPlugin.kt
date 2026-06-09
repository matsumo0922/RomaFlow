package primitive

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * KMP Android library module に AGP 9 向け Android library plugin を適用するプラグイン。
 */
class AndroidLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.kotlin.multiplatform.library")
                apply("kotlin-parcelize")
                apply("kotlinx-serialization")
                apply("project-report")
                apply("com.google.devtools.ksp")
            }
        }
    }
}

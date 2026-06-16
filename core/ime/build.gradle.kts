import com.codingfeline.buildkonfig.compiler.FieldSpec.Type.STRING
import org.jetbrains.kotlin.gradle.swiftexport.ExperimentalSwiftExportDsl
import java.util.Properties

plugins {
    id("matsumo.primitive.kmp.common")
    id("matsumo.primitive.android.library")
    id("matsumo.primitive.kmp.android")
    id("matsumo.primitive.detekt")
    alias(libs.plugins.build.konfig)
}

// local.properties から OpenAI 設定を読み込む。未設定なら空文字などの既定値でビルドを通す。
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")

    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

fun localProperty(key: String, defaultValue: String): String {
    return localProperties.getProperty(key, defaultValue)
}

kotlin {
    android {
        namespace = "me.matsumo.romaflow.core.ime"
    }

    macosArm64()

    @OptIn(ExperimentalSwiftExportDsl::class)
    swiftExport {
        moduleName = "RomaFlowImeCore"
        flattenPackage = "me.matsumo.romaflow.core.ime"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.wanakana)
            implementation(libs.napier)
            implementation(libs.ktor.core)
            implementation(libs.ktor.content.negotiation)
            implementation(libs.ktor.serialization.json)
        }

        androidMain.dependencies {
            implementation(libs.ktor.okhttp)
        }

        macosArm64Main.dependencies {
            implementation(libs.ktor.darwin)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

buildkonfig {
    packageName = "me.matsumo.romaflow.core.ime"

    defaultConfigs {
        buildConfigField(STRING, "OPENAI_API_KEY", localProperty("OPENAI_API_KEY", ""))
        buildConfigField(STRING, "OPENAI_BASE_URL", localProperty("OPENAI_BASE_URL", "https://api.openai.com/v1"))
        buildConfigField(STRING, "OPENAI_MODEL", localProperty("OPENAI_MODEL", "gpt-5-nano"))
    }
}

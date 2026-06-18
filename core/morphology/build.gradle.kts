plugins {
    id("matsumo.primitive.kmp.common")
    id("matsumo.primitive.android.library")
    id("matsumo.primitive.kmp.android")
    id("matsumo.primitive.detekt")
}

kotlin {
    android {
        namespace = "me.matsumo.romaflow.core.morphology"

        // PoC: commonTest を Android（JVM host）でも実行し、momiji の jvm 変種が
        // Android target から解決・動作することを検証する。
        withHostTest {}
    }

    macosArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.momiji.core)
            implementation(libs.momiji.ipadic)

            // data class の安定性アノテーション(@Immutable)用。compose runtime のみを参照する。
            implementation(libs.compose.runtime)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

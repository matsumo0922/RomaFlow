plugins {
    id("matsumo.primitive.kmp.common")
    id("matsumo.primitive.android.library")
    id("matsumo.primitive.kmp.compose")
    id("matsumo.primitive.kmp.android")
    id("matsumo.primitive.kmp.ios")
    id("matsumo.primitive.detekt")
}

compose.resources {
    publicResClass = true
    packageOfResClass = "me.matsumo.romaflow.core.resource"
    generateResClass = always
}

kotlin {
    android {
        namespace = "me.matsumo.romaflow.core.resource"
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.compose.runtime)
            api(libs.compose.resources)
        }
    }
}

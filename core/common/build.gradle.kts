plugins {
    id("matsumo.primitive.kmp.common")
    id("matsumo.primitive.android.library")
    id("matsumo.primitive.kmp.android")
    id("matsumo.primitive.kmp.ios")
    id("matsumo.primitive.detekt")
}

kotlin {
    android {
        namespace = "me.matsumo.romaflow.core.common"
    }

    sourceSets {
        commonMain.dependencies {
            api(project.dependencies.platform(libs.koin.bom))

            api(libs.bundles.infra)
            api(libs.bundles.koin)
        }

        androidMain.dependencies {
            api(project.dependencies.platform(libs.firebase.bom))

            api(libs.bundles.firebase)
            api(libs.koin.android)
        }
    }
}

@file:Suppress("UnstableApiUsage")

rootProject.name = "RomaFlow"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://storage.googleapis.com/r8-releases/raw")
        maven("https://jitpack.io")
        // WanaKana の macosArm64 対応 fork を Maven Local から解決する暫定措置。
        // upstream PR (GreatTusk/wanakana-kmp#1) がマージ・リリースされたら撤去する。`make setup-wanakana` を参照。
        mavenLocal {
            content {
                includeGroup("io.github.greattusk")
            }
        }
    }
}

include(":shared")
include(":androidApp")
include(":core:common")
include(":core:ui")
include(":core:datasource")
include(":core:repository")
include(":core:resource")
include(":core:model")
include(":core:billing")
include(":core:ime")
include(":feature:home")
include(":feature:setting")
include(":feature:billing")

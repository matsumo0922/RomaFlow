package me.matsumo.romaflow.core.model

expect val currentPlatform: Platform

enum class Platform {
    Android,
    IOS,
}

package me.matsumo.romaflow.core.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import me.matsumo.romaflow.core.model.AppConfig
import me.matsumo.romaflow.core.model.AppSetting

val LocalAppSetting = staticCompositionLocalOf {
    AppSetting.DEFAULT
}

val LocalAppConfig = staticCompositionLocalOf<AppConfig> {
    error("No AppConfig provided")
}

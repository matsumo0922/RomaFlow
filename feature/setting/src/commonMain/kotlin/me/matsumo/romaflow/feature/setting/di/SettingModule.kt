package me.matsumo.romaflow.feature.setting.di

import me.matsumo.romaflow.feature.setting.SettingViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val settingModule = module {
    viewModelOf(::SettingViewModel)
}

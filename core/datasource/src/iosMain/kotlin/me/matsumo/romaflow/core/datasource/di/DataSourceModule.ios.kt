package me.matsumo.romaflow.core.datasource.di

import me.matsumo.romaflow.core.datasource.helper.PreferenceHelper
import me.matsumo.romaflow.core.datasource.helper.PreferenceHelperImpl
import org.koin.core.module.Module
import org.koin.dsl.module

internal actual val dataSourcePlatformModule: Module = module {
    single<PreferenceHelper> {
        PreferenceHelperImpl(
            ioDispatcher = get(),
        )
    }
}

package me.matsumo.romaflow.di

import me.matsumo.romaflow.MainViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Android アプリケーション層の依存関係を提供する Koin モジュール。
 */
internal val androidAppModule = module {
    viewModelOf(::MainViewModel)
}

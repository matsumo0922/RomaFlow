package me.matsumo.romaflow.feature.home.di

import me.matsumo.romaflow.feature.home.HomeViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val homeModule = module {
    viewModelOf(::HomeViewModel)
}

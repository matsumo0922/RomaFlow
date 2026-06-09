package me.matsumo.romaflow.feature.billing.di

import me.matsumo.romaflow.feature.billing.PaywallViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val billingFeatureModule = module {
    viewModelOf(::PaywallViewModel)
}

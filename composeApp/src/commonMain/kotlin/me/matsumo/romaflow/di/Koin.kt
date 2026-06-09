package me.matsumo.romaflow.di

import me.matsumo.romaflow.core.billing.di.billingModule
import me.matsumo.romaflow.core.common.di.commonModule
import me.matsumo.romaflow.core.datasource.di.dataSourceModule
import me.matsumo.romaflow.core.repository.di.repositoryModule
import me.matsumo.romaflow.feature.billing.di.billingFeatureModule
import me.matsumo.romaflow.feature.home.di.homeModule
import me.matsumo.romaflow.feature.setting.di.settingModule
import org.koin.core.KoinApplication

fun KoinApplication.applyModules() {
    modules(appModule)

    modules(commonModule)
    modules(billingModule)
    modules(dataSourceModule)
    modules(repositoryModule)

    modules(homeModule)
    modules(settingModule)
    modules(billingFeatureModule)
}

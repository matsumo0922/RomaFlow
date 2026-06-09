package me.matsumo.romaflow.core.repository.di

import me.matsumo.romaflow.core.repository.AppSettingRepository
import me.matsumo.romaflow.core.repository.BillingRepository
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val repositoryModule = module {
    singleOf(::AppSettingRepository)
    singleOf(::BillingRepository)
}

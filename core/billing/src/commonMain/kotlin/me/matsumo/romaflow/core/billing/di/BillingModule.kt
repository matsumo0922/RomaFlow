package me.matsumo.romaflow.core.billing.di

import me.matsumo.romaflow.core.billing.BillingDataSource
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val billingModule = module {
    singleOf(::BillingDataSource)
}

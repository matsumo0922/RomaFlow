package me.matsumo.romaflow

import android.app.Application
import io.github.aakira.napier.DebugAntilog
import io.github.aakira.napier.Napier
import me.matsumo.romaflow.di.androidAppModule
import me.matsumo.romaflow.di.applyModules
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androix.startup.KoinStartup
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.dsl.koinConfiguration

/**
 * RomaFlow の Android アプリケーション初期化を担当する Application。
 */
@OptIn(KoinExperimentalAPI::class)
class RomaFlowApplication : Application(), KoinStartup {
    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            // StrictMode.enableDefaults()
            Napier.base(DebugAntilog())
        }
    }

    override fun onKoinStartup() = koinConfiguration {
        androidContext(this@RomaFlowApplication)
        androidLogger()
        applyModules()
        modules(androidAppModule)
    }
}

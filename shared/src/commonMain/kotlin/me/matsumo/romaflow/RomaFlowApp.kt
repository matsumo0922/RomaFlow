package me.matsumo.romaflow

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lexilabs.basic.ads.BasicAds
import app.lexilabs.basic.ads.DependsOnGoogleMobileAds
import app.lexilabs.basic.ads.DependsOnGoogleUserMessagingPlatform
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.request.crossfade
import io.github.vinceglb.filekit.coil.addPlatformFileSupport
import me.matsumo.romaflow.core.model.AppSetting
import me.matsumo.romaflow.core.ui.theme.RomaFlowTheme

@OptIn(DependsOnGoogleMobileAds::class, DependsOnGoogleUserMessagingPlatform::class)
@Composable
fun RomaFlowApp(
    setting: AppSetting,
    modifier: Modifier = Modifier,
) {
    SetupCoil()
    BasicAds.Initialize()

    RomaFlowTheme(setting) {
        AppNavHost(modifier)
    }
}

@Composable
private fun SetupCoil() {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components {
                addPlatformFileSupport()
            }
            .crossfade(true)
            .build()
    }
}

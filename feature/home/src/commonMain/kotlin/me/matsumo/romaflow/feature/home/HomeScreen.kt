package me.matsumo.romaflow.feature.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import me.matsumo.romaflow.core.resource.Res
import me.matsumo.romaflow.core.resource.google_sans_bold
import me.matsumo.romaflow.core.resource.google_sans_medium
import me.matsumo.romaflow.core.resource.google_sans_regular
import me.matsumo.romaflow.core.resource.home_title
import me.matsumo.romaflow.core.ui.screen.Destination
import me.matsumo.romaflow.core.ui.theme.LocalNavBackStack
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    modifier: Modifier = Modifier,
) {
    val navBackStack = LocalNavBackStack.current

    val currentIndex = rememberSaveable { mutableIntStateOf(0) }
    val saveableStateHolder: SaveableStateHolder = rememberSaveableStateHolder()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    LaunchedEffect(currentIndex.intValue) {
        scrollBehavior.state.heightOffset = 0f
    }

    NavigationSuiteScaffold(
        modifier = modifier,
        navigationSuiteItems = {
            for ((index, destination) in HomeNavDestination.all.withIndex()) {
                item(
                    selected = currentIndex.intValue == index,
                    onClick = { currentIndex.intValue = index },
                    icon = {
                        Icon(
                            imageVector = if (currentIndex.intValue == index) destination.iconSelected else destination.icon,
                            contentDescription = stringResource(destination.label),
                        )
                    },
                    label = {
                        Text(
                            text = stringResource(destination.label),
                        )
                    },
                )
            }
        },
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                HomeTopAppBar(
                    modifier = Modifier.fillMaxWidth(),
                    scrollBehavior = scrollBehavior,
                    onSettingClicked = { navBackStack.add(Destination.Setting.Root) },
                )
            },
        ) { contentPadding ->
            AnimatedContent(
                modifier = Modifier.fillMaxSize(),
                targetState = currentIndex.intValue,
            ) { index ->
                saveableStateHolder.SaveableStateProvider(index) {
                    when (HomeNavDestination.all[index].route) {
                        HomeRoute.Photos -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "Photos",
                                    style = MaterialTheme.typography.titleLarge,
                                )
                            }
                        }

                        HomeRoute.Downloads -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "Downloads",
                                    style = MaterialTheme.typography.titleLarge,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopAppBar(
    scrollBehavior: TopAppBarScrollBehavior,
    onSettingClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val googleSansFamily = FontFamily(
        Font(Res.font.google_sans_regular, FontWeight.Normal),
        Font(Res.font.google_sans_medium, FontWeight.Medium),
        Font(Res.font.google_sans_bold, FontWeight.Bold),
    )

    TopAppBar(
        modifier = modifier,
        scrollBehavior = scrollBehavior,
        title = {
            Text(
                text = stringResource(Res.string.home_title),
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = googleSansFamily,
                fontWeight = FontWeight.SemiBold,
            )
        },
        actions = {
            IconButton(onClick = onSettingClicked) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                )
            }
        },
    )
}

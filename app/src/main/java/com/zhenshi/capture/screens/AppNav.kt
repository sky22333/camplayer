package com.zhenshi.capture.screens

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.zhenshi.capture.R
import com.zhenshi.capture.navigation.AppNavigationRequests
import com.zhenshi.capture.screens.components.LocalAppSnackbar
import com.zhenshi.capture.screens.components.RequestAppRuntimePermissionsOnLaunch
import com.zhenshi.capture.screens.network.NetworkScreen
import com.zhenshi.capture.screens.player.PlayerScreen
import com.zhenshi.capture.screens.push.PushScreen
import com.zhenshi.capture.screens.settings.SettingsScreen
import com.zhenshi.capture.screens.usb.UsbScreen

object Routes {
    const val Usb = "usb"
    const val Network = "network"
    const val Push = "push"
    const val Settings = "settings"
    const val Player = "player?source={source}&profile={profile}"
    fun player(source: String, profile: String = ""): String =
        "player?source=${android.net.Uri.encode(source)}&profile=${android.net.Uri.encode(profile)}"
}

private data class TopDest(
    val route: String,
    val labelRes: Int,
    val cdRes: Int,
    val icon: ImageVector,
)

@Composable
fun AppNav(
    navigationRequests: AppNavigationRequests,
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val hideBar = destination?.route?.startsWith("player") == true
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

    RequestAppRuntimePermissionsOnLaunch()

    LaunchedEffect(navigationRequests) {
        navigationRequests.openUsbTabEvents.collect {
            val currentRoute = navController.currentDestination?.route
            if (currentRoute == Routes.Usb || currentRoute?.startsWith("player") == true) return@collect
            navController.navigateToTopLevel(Routes.Usb)
        }
    }

    val items = listOf(
        TopDest(Routes.Usb, R.string.nav_devices, R.string.cd_nav_devices, Icons.Outlined.Videocam),
        TopDest(Routes.Network, R.string.nav_network, R.string.cd_nav_network, Icons.Outlined.WifiTethering),
        TopDest(Routes.Push, R.string.nav_push, R.string.cd_nav_push, Icons.Outlined.Upload),
        TopDest(Routes.Settings, R.string.nav_settings, R.string.cd_nav_settings, Icons.Outlined.Settings),
    )

    CompositionLocalProvider(LocalAppSnackbar provides snackbarHostState) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surface,
            contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!hideBar) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                    ) {
                        val scheme = MaterialTheme.colorScheme
                        items.forEach { item ->
                            val selected = destination?.hierarchy?.any { it.route == item.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    if (selected) return@NavigationBarItem
                                    navController.navigateToTopLevel(item.route)
                                },
                                icon = {
                                    Icon(
                                        item.icon,
                                        contentDescription = stringResource(item.cdRes),
                                    )
                                },
                                label = { Text(stringResource(item.labelRes)) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = scheme.primary,
                                    selectedTextColor = scheme.primary,
                                    indicatorColor = scheme.surface,
                                    unselectedIconColor = scheme.onSurfaceVariant,
                                    unselectedTextColor = scheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Routes.Usb,
                modifier = Modifier.fillMaxSize(),
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None },
            ) {
                composable(Routes.Usb) {
                    UsbScreen(
                        contentPadding = innerPadding,
                        onPreview = { sourceKey, profileKey ->
                            navController.navigate(Routes.player(sourceKey, profileKey)) {
                                launchSingleTop = true
                            }
                        },
                        onOpenRecent = { key ->
                            navController.navigate(Routes.player(key)) {
                                launchSingleTop = true
                            }
                        },
                    )
                }
                composable(Routes.Network) {
                    NetworkScreen(
                        contentPadding = innerPadding,
                        onPlay = { url ->
                            navController.navigate(Routes.player(url)) {
                                launchSingleTop = true
                            }
                        },
                    )
                }
                composable(Routes.Push) {
                    PushScreen(contentPadding = innerPadding)
                }
                composable(Routes.Settings) {
                    SettingsScreen(contentPadding = innerPadding)
                }
                composable(
                    route = Routes.Player,
                    arguments = listOf(
                        navArgument("source") { type = NavType.StringType; defaultValue = "" },
                        navArgument("profile") { type = NavType.StringType; defaultValue = "" },
                    ),
                    // 进场短淡入；退场无动画
                    enterTransition = { fadeIn(animationSpec = tween(90)) },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None },
                ) { entry ->
                    val source = entry.arguments?.getString("source").orEmpty()
                    val profile = entry.arguments?.getString("profile").orEmpty()
                    PlayerScreen(
                        sourceKey = source,
                        profileKey = profile,
                        onBack = { navController.popBackStack() },
                        onManagePushTargets = {
                            navController.navigateToTopLevel(Routes.Push)
                        },
                    )
                }
            }
        }
    }
}

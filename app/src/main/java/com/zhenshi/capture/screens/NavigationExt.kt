package com.zhenshi.capture.screens

import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController

fun NavHostController.navigateToTopLevel(route: String) {
    val currentRoute = currentDestination?.route
    if (currentRoute == route) return

    val startRoute = graph.findStartDestination().route
    if (route == startRoute && previousBackStackEntry != null) {
        if (popBackStack(route, inclusive = false)) return
    }

    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

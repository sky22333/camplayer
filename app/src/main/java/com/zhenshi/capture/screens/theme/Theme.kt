package com.zhenshi.capture.screens.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = BrandGreen,
    onPrimary = OnBrandGreen,
    primaryContainer = BrandGreenContainer,
    onPrimaryContainer = OnBrandGreenContainer,
    secondary = OnBrandGreenContainer,
    secondaryContainer = BrandGreenContainer,
    onSecondaryContainer = OnBrandGreenContainer,
    background = DarkSurface,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    onSurfaceVariant = DarkOnVariant,
    surfaceContainerLowest = DarkSurface,
    surfaceContainerLow = DarkContainer,
    surfaceContainer = DarkContainer,
    surfaceContainerHigh = DarkFill,
    surfaceContainerHighest = DarkFillStrong,
    outline = DarkLine,
    outlineVariant = DarkLine.copy(alpha = 0.65f),
)

/** 固定暗色扁平主题；不跟随系统浅色 / 壁纸动态取色。 */
@Composable
fun ZhenShiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = ZhenShiTypography,
        shapes = ZhenShiShapes,
        content = content,
    )
}

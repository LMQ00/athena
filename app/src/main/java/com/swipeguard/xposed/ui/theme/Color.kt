package com.swipeguard.xposed.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Material 3 调色板。
 *
 * 以种子色 [Seed]（Google 蓝 #1A73E8）为基准，覆盖亮色与暗色两套方案。
 * 颜色取值参考 Material Theme Builder 对 #1A73E8 生成的方案，并稍作收敛
 * 以适配 Android 设置类界面的高可读性需求。
 */

// ---- 种子色 ----
val Seed: Color = Color(0xFF1A73E8)

// ---- 亮色方案 ----
val LightPrimary: Color = Color(0xFF0B57D0)
val LightOnPrimary: Color = Color(0xFFFFFFFF)
val LightPrimaryContainer: Color = Color(0xFFD3E4FF)
val LightOnPrimaryContainer: Color = Color(0xFF001D34)
val LightSecondary: Color = Color(0xFF565F71)
val LightOnSecondary: Color = Color(0xFFFFFFFF)
val LightSecondaryContainer: Color = Color(0xFFD9E3F8)
val LightOnSecondaryContainer: Color = Color(0xFF131C2B)
val LightTertiary: Color = Color(0xFF705575)
val LightTertiaryContainer: Color = Color(0xFFFAD8F2)
val LightBackground: Color = Color(0xFFFDFCFF)
val LightOnBackground: Color = Color(0xFF1A1C1E)
val LightSurface: Color = Color(0xFFFDFCFF)
val LightOnSurface: Color = Color(0xFF1A1C1E)
val LightSurfaceVariant: Color = Color(0xFFDFE2EB)
val LightOnSurfaceVariant: Color = Color(0xFF42474E)
val LightError: Color = Color(0xFFBA1A1A)
val LightOnError: Color = Color(0xFFFFFFFF)
val LightErrorContainer: Color = Color(0xFFFFDAD6)
val LightOutline: Color = Color(0xFF73777F)

// ---- 暗色方案 ----
val DarkPrimary: Color = Color(0xFFA2C9FF)
val DarkOnPrimary: Color = Color(0xFF002F66)
val DarkPrimaryContainer: Color = Color(0xFF00468F)
val DarkOnPrimaryContainer: Color = Color(0xFFD3E4FF)
val DarkSecondary: Color = Color(0xFFBDC7DC)
val DarkOnSecondary: Color = Color(0xFF273141)
val DarkSecondaryContainer: Color = Color(0xFF3D4858)
val DarkOnSecondaryContainer: Color = Color(0xFFD9E3F8)
val DarkTertiary: Color = Color(0xFFDDBDD4)
val DarkTertiaryContainer: Color = Color(0xFF563E5C)
val DarkBackground: Color = Color(0xFF1A1C1E)
val DarkOnBackground: Color = Color(0xFFE3E2E6)
val DarkSurface: Color = Color(0xFF1A1C1E)
val DarkOnSurface: Color = Color(0xFFE3E2E6)
val DarkSurfaceVariant: Color = Color(0xFF42474E)
val DarkOnSurfaceVariant: Color = Color(0xFFC2C7CF)
val DarkError: Color = Color(0xFFFFB4AB)
val DarkOnError: Color = Color(0xFF690005)
val DarkErrorContainer: Color = Color(0xFF93000A)
val DarkOutline: Color = Color(0xFF8C9198)

/**
 * 亮色主题方案。
 */
val LightSwipeGuardColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    tertiaryContainer = LightTertiaryContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    outline = LightOutline,
)

/**
 * 暗色主题方案。
 */
val DarkSwipeGuardColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    outline = DarkOutline,
)

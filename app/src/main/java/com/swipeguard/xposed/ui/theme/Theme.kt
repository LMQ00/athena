package com.swipeguard.xposed.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * SwipeGuard 顶层主题包装。
 *
 * 优先级：
 *  1. Android 12+（API 31+）使用系统动态取色（[dynamicLightColorScheme] /
 *     [dynamicDarkColorScheme]），保证与系统设置一致性体验；
 *  2. 其余版本回退到 [LightSwipeGuardColors] / [DarkSwipeGuardColors] 静态方案，
 *     种子色为 [Seed]（#1A73E8）。
 *
 * [darkTheme] 默认跟随系统暗色模式；[dynamicColor] 默认开启，可在调试时关闭。
 */
@Composable
fun SwipeGuardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkSwipeGuardColors
        else -> LightSwipeGuardColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SwipeGuardTypography,
        content = content,
    )
}

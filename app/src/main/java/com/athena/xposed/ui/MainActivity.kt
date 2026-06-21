package com.athena.xposed.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.athena.xposed.ui.theme.AthenaTheme

/**
 * 设置主界面宿主 Activity。
 *
 * 单 Activity 架构：所有页面通过 Navigation Compose 在 [AthenaApp] 内切换，
 * 本类仅负责挂载主题与根 Composable。
 *
 * [enableEdgeToEdge] 启用边到边布局，让底部导航栏与状态栏适配系统 insets
 * （由 Scaffold 的 innerPadding 自动消费）。
 *
 * 在 `AndroidManifest.xml` 中以 `.ui.MainActivity` 路径声明，并带 LAUNCHER
 * intent-filter 作为应用入口。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AthenaTheme {
                AthenaApp()
            }
        }
    }
}

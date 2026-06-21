package com.swipeguard.xposed.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.swipeguard.xposed.SwipeGuardApplication
import com.swipeguard.xposed.ui.data.SwipeGuardViewModel
import com.swipeguard.xposed.ui.screens.SwipeGuardScreen
import com.swipeguard.xposed.ui.theme.SwipeGuardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 确保 ViewModel 已初始化
        SwipeGuardViewModel.init(application)

        setContent {
            SwipeGuardTheme {
                SwipeGuardScreen()
            }
        }
    }
}

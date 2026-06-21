package com.athena.xposed.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.athena.xposed.ui.navigation.AboutRoute
import com.athena.xposed.ui.navigation.AthenaBottomBar
import com.athena.xposed.ui.navigation.BlacklistRoute
import com.athena.xposed.ui.navigation.DebugRoute
import com.athena.xposed.ui.navigation.HomeRoute
import com.athena.xposed.ui.navigation.WhitelistRoute
import com.athena.xposed.ui.screens.about.AboutScreen
import com.athena.xposed.ui.screens.blacklist.BlacklistScreen
import com.athena.xposed.ui.screens.debug.DebugScreen
import com.athena.xposed.ui.screens.home.HomeScreen
import com.athena.xposed.ui.screens.whitelist.WhitelistScreen

/**
 * 顶层应用 Composable。
 *
 * 结构：
 *  - [Scaffold] 提供底部导航栏（[AthenaBottomBar]），content 区域消费其
 *    innerPadding 以避免内容被导航栏遮挡；
 *  - [NavHost] 声明 5 个 type-safe 路由：Home / Whitelist / Blacklist /
 *    Debug / About；起点为 [HomeRoute]；
 *  - 每个目的地通过 `composable<Route>()` 注册对应 Screen。
 */
@Composable
fun AthenaApp() {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = { AthenaBottomBar(navController) }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = HomeRoute,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable<HomeRoute> { HomeScreen(navController) }
            composable<WhitelistRoute> { WhitelistScreen(navController) }
            composable<BlacklistRoute> { BlacklistScreen(navController) }
            composable<DebugRoute> { DebugScreen() }
            composable<AboutRoute> { AboutScreen() }
        }
    }
}

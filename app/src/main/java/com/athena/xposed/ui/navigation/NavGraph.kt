package com.athena.xposed.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 底部导航栏 Tab 描述。
 *
 * @param route 对应的 type-safe 路由对象（用于 [NavController.navigate]）。
 * @param label 显示在 Tab 下方的文案。
 * @param selectedIcon 选中时的填充图标。
 * @param unselectedIcon 未选中时的轮廓图标。
 */
private data class AthenaTab(
    val route: Any,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

/**
 * 底部 4 个 Tab：首页 / 白名单 / 黑名单 / 调试。
 *
 * About 页不在此处展示，仅通过 Home 页快捷入口跳转。
 */
private val AthenaTabs: List<AthenaTab> = listOf(
    AthenaTab(HomeRoute, "首页", Icons.Filled.Home, Icons.Outlined.Home),
    AthenaTab(WhitelistRoute, "白名单", Icons.Filled.VerifiedUser, Icons.Outlined.VerifiedUser),
    AthenaTab(BlacklistRoute, "黑名单", Icons.Filled.Checklist, Icons.Outlined.Checklist),
    AthenaTab(DebugRoute, "调试", Icons.Filled.BugReport, Icons.Outlined.BugReport),
)

/**
 * 底部导航栏。
 *
 * 跟随当前 NavBackStackEntry 自动高亮对应 Tab；点击时通过
 * [NavController.navigate] 跳转，并采用「save state + restore state」
 * 模式保留各 Tab 的滚动位置与 ViewModel 状态。
 *
 * 当前路由不匹配任何 Tab（如 About 页）时，所有 Tab 都不高亮。
 */
@Composable
fun AthenaBottomBar(navController: NavController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    NavigationBar {
        AthenaTabs.forEach { tab ->
            val selected = currentRoute == routeKeyOf(tab.route)
            NavigationBarItem(
                selected = selected,
                onClick = {
                    navController.navigate(tab.route) {
                        // 弹出至起点，保留起点状态
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = {
                    Icon(
                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                        contentDescription = tab.label,
                    )
                },
                label = { Text(tab.label) },
            )
        }
    }
}

/**
 * 将 type-safe 路由对象转换为对应 NavDestination 的 route 字符串。
 *
 * Navigation Compose 对 `@Serializable data object` 生成的 route 为
 * 该对象序列化器的 `descriptor.serialName`，默认为类的完全限定名
 * （如 `com.athena.xposed.ui.navigation.HomeRoute`）。此处取
 * [KClass.getQualifiedName] 作为等价比较键。
 */
private fun routeKeyOf(route: Any): String =
    route::class.qualifiedName ?: error("Route object has no qualified name: $route")

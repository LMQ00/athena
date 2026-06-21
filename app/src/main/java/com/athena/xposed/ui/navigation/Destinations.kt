package com.athena.xposed.ui.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe 导航路由定义。
 *
 * 使用 Navigation Compose 2.8+ 的 `@Serializable` 路由模式：
 *  - 每个路由为单例 `data object`，序列化器由 kotlinx-serialization 自动生成；
 *  - 在 [com.athena.xposed.ui.AthenaApp] 的 `NavHost` 中通过
 *    `composable<HomeRoute>()` 形式声明；
 *  - 跳转使用 `navController.navigate(HomeRoute)`，类型安全且避免字符串拼写错误。
 *
 * 设计原则：
 *  - 路由对象本身不携带参数（无参路由），复杂参数通过 [ConfigViewModel]
 *    单例传递，避免序列化大对象。
 *  - 顶部 4 个 Tab 对应 [HomeRoute] / [WhitelistRoute] / [BlacklistRoute] /
 *    [DebugRoute]；[AboutRoute] 仅通过 Home 页快捷入口进入，不展示在底部栏。
 */
@Serializable data object HomeRoute

@Serializable data object WhitelistRoute

@Serializable data object BlacklistRoute

@Serializable data object DebugRoute

@Serializable data object AboutRoute

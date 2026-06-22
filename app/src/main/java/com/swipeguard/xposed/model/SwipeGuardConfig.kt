package com.swipeguard.xposed.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * SwipeGuard 配置根容器。
 *
 * 极简设计：只保存 Athena 白名单中需要添加的 app 包名集合。
 * 整个配置就是一个 JSON 对象，通过 SharedPreferences 跨进程共享。
 */
@Serializable
data class SwipeGuardConfig(
    /** 模块总开关 */
    var enabled: Boolean = true,

    /** 需要加入 Athena 白名单的 app 包名集合 */
    var protectedApps: Set<String> = emptySet(),

    /**
     * 写入 <whitePkg category="..."/> 的 category 编码。
     *
     * 三位独立编码（每位表示一个维度）：
     * - `100` = forcewhite（系统级强制白名单，不被杀）
     * - `010` = oppo/oneplus 自有应用
     * - `001` = 第三方应用
     */
    var whitelistCategory: String = "100",

    /** schema 版本，用于向前兼容 */
    val schemaVersion: Int = 1
) {
    companion object {
        /** 空配置默认值 */
        val DEFAULT = SwipeGuardConfig()

        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = false
            encodeDefaults = false
        }

        fun fromJson(s: String): SwipeGuardConfig =
            runCatching { json.decodeFromString(serializer(), s) }
                .getOrDefault(DEFAULT)

        fun toJson(config: SwipeGuardConfig): String =
            json.encodeToString(serializer(), config)
    }
}

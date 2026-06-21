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

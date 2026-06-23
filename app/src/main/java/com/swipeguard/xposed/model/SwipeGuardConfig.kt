package com.swipeguard.xposed.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * SwipeGuard 配置根容器。
 *
 * 双层白名单设计：
 * - **系统默认白名单**（[systemDefaults]）：预置的 ColorOS 系统默认白名单应用，
 *   基于对 `com.oplus.athena` v6.0.1 的逆向分析结果。
 * - **用户修改**：
 *   - [userAdditions]：用户额外添加的包名
 *   - [userRemovals]：用户从系统默认白名单中移除的包名
 *
 * 有效白名单 = (systemDefaults - userRemovals) + userAdditions
 *
 * 整个配置就是一个 JSON 对象，通过 SharedPreferences 跨进程共享。
 */
@Serializable
data class SwipeGuardConfig(
    /** 模块总开关 */
    var enabled: Boolean = true,

    /** 用户额外添加的白名单包名 */
    var userAdditions: Set<String> = emptySet(),

    /** 用户从系统默认白名单中移除的包名 */
    var userRemovals: Set<String> = emptySet(),

    /** 系统级默认白名单（逆向 Athena APK 提取 + 预置常用应用） */
    var systemDefaults: Set<String> = emptySet(),

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
    val schemaVersion: Int = 2
) {
    /** 有效白名单 = 系统默认 - 用户移除 + 用户添加 */
    val effectiveProtectedApps: Set<String>
        get() = (systemDefaults - userRemovals) + userAdditions

    companion object {
        /** 空配置默认值 */
        val DEFAULT = SwipeGuardConfig()

        /** 预置的 ColorOS 系统默认白名单应用 */
        val KNOWN_SYSTEM_DEFAULTS: Set<String> = setOf(
            // 系统核心应用 (forcewhite 级别)
            "com.coloros.soundrecorder",    // 录音机
            "com.oplus.melody",             // 铃声

            // 常见高频使用应用（用户期望不被打断）
            "com.tencent.mm",               // 微信
            "com.tencent.mobileqq",          // QQ
            "com.eg.android.AlipayGphone",   // 支付宝
            "com.tencent.wework",            // 企业微信
            "com.alibaba.android.rimet",     // 钉钉
        )

        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = false
            encodeDefaults = false
        }

        /**
         * 从 JSON 字符串反序列化配置。
         *
         * 自动处理 schema v1 → v2 迁移：
         * 旧版使用 `protectedApps` 字段 → 新版 `userAdditions` + `userRemovals`。
         */
        fun fromJson(s: String): SwipeGuardConfig =
            runCatching {
                val migrated = migrateFromV1(s)
                val cfg = json.decodeFromString(serializer(), migrated)
                // 首次使用时 systemDefaults 为空，填充预置默认值
                if (cfg.systemDefaults.isEmpty()) cfg.copy(systemDefaults = KNOWN_SYSTEM_DEFAULTS)
                else cfg
            }.getOrDefault(DEFAULT)

        fun toJson(config: SwipeGuardConfig): String =
            json.encodeToString(serializer(), config)

        /**
         * schema v1 → v2 迁移：
         * 将旧 `protectedApps` 字段重命名为 `userAdditions`，
         * 并将 `schemaVersion` 设为 2。
         */
        private fun migrateFromV1(jsonStr: String): String {
            if (!jsonStr.contains("\"protectedApps\"")) return jsonStr
            return try {
                val element = json.parseToJsonElement(jsonStr)
                if (element !is JsonObject) return jsonStr
                val obj = element.jsonObject.toMutableMap()
                if (obj.containsKey("protectedApps") && !obj.containsKey("userAdditions")) {
                    obj["userAdditions"] = obj["protectedApps"]!!
                    obj.remove("protectedApps")
                    obj["schemaVersion"] = JsonPrimitive(2)
                    json.encodeToString(JsonElement.serializer(), JsonObject(obj))
                } else {
                    jsonStr
                }
            } catch (_: Throwable) {
                jsonStr
            }
        }
    }
}

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
 * - **系统默认白名单**（[systemDefaults]）：从 ColorOS `sys_elsa_config_list.xml`
 *   中提取的 OEM 预设白名单，由 Hook 进程写入 SharedPreferences。
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
    /** 计算有效白名单：系统默认 - 用户移除 + 用户添加 */
    fun effectiveProtectedApps(systemDefaults: Set<String>): Set<String> =
        (systemDefaults - userRemovals) + userAdditions

    companion object {
        /** 空配置默认值 */
        val DEFAULT = SwipeGuardConfig()

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
                json.decodeFromString(serializer(), migrated)
            }.getOrDefault(DEFAULT)

        fun toJson(config: SwipeGuardConfig): String =
            json.encodeToString(serializer(), config)

        /**
         * schema v1 → v2 迁移：
         * 将旧 `protectedApps` 字段重命名为 `userAdditions`，
         * 并将 `schemaVersion` 设为 2。
         */
        private fun migrateFromV1(jsonStr: String): String {
            // Fast path: no migration needed if protectedApps key is absent
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
                // Migration failed — proceed with original string;
                // unknown keys will be ignored by the decoder.
                jsonStr
            }
        }
    }
}

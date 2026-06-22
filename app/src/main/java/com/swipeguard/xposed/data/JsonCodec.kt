package com.swipeguard.xposed.data

import com.swipeguard.xposed.model.SwipeGuardConfig
import kotlinx.serialization.json.Json

object JsonCodec {
    private val setJson = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = false
    }

    fun encode(config: SwipeGuardConfig): String = SwipeGuardConfig.toJson(config)
    fun decode(jsonStr: String): SwipeGuardConfig = SwipeGuardConfig.fromJson(jsonStr)

    /** 编码 [Set]<[String]> 为 JSON 数组字符串 */
    fun encodeSet(set: Set<String>): String =
        setJson.encodeToString(set)

    /** 从 JSON 数组字符串解码 [Set]<[String]>；解析失败返回空集合 */
    fun decodeSet(jsonStr: String): Set<String> =
        runCatching {
            setJson.decodeFromString(jsonStr)
        }.getOrDefault(emptySet())
}

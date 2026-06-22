package com.swipeguard.xposed.data

import com.swipeguard.xposed.model.SwipeGuardConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JSON 编解码工具。
 *
 * 使用 [kotlinx.serialization] 统一处理 [SwipeGuardConfig] 及
 * [Set]<[String]> 的 JSON 序列化/反序列化。
 */
object JsonCodec {

    /**
     * 包装类型，使 [Set]<[String]> 可通过 kotlinx.serialization 编解码。
     * 使用内部 data class 避免 [kotlinx.serialization.builtins.SetSerializer] 的
     * 类型推断兼容性问题。
     */
    @Serializable
    private data class StringSet(val items: List<String>)

    private val setJson = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = false
    }

    fun encode(config: SwipeGuardConfig): String = SwipeGuardConfig.toJson(config)
    fun decode(jsonStr: String): SwipeGuardConfig = SwipeGuardConfig.fromJson(jsonStr)

    /** 编码 [Set]<[String]> 为 JSON 数组字符串 */
    fun encodeSet(set: Set<String>): String =
        setJson.encodeToString(StringSet.serializer(), StringSet(set.toList()))

    /** 从 JSON 数组字符串解码 [Set]<[String]>；解析失败返回空集合 */
    fun decodeSet(jsonStr: String): Set<String> =
        runCatching {
            setJson.decodeFromString(StringSet.serializer(), jsonStr).items.toSet()
        }.getOrDefault(emptySet())
}

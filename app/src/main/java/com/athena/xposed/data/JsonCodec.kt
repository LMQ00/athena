package com.athena.xposed.data

import com.athena.xposed.model.AthenaConfig
import kotlinx.serialization.json.Json

/**
 * [AthenaConfig] 与 JSON 字符串之间的双向编解码器。
 *
 * 该对象是无状态、线程安全的（[Json] 实例本身线程安全），可被 UI 进程与
 * Hook 进程同时持有。两端使用同一份编码规则，保证跨进程语义一致。
 *
 * 设计要点：
 * - [Json.ignoreUnknownKeys]：跨进程/跨版本字段增删时不抛异常，保证向后兼容。
 * - [Json.prettyPrint] = false：Hook 进程每次读取都会反序列化整段 JSON，紧凑格式
 *   减少 Binder 传输与磁盘体积。
 * - [Json.encodeDefaults] = false：默认值不写入，避免 UI 修改一个字段后把所有默认
 *   值都固化为显式值，从而在新增字段默认值变更时无法生效。
 * - [Json.isLenient] = false：保留严格引号规则，写入端由本类统一编码，避免外部
 *   传入畸形 JSON 被静默接受。
 *
 * 异常策略：本类仅负责编解码本身，不吞掉异常。调用方（Repository）负责在
 * 反序列化失败时回退到 [AthenaConfig.DEFAULT]。
 */
object JsonCodec {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = false
        isLenient = false
        // 预留：当 model 引入需要自定义序列化器的类型（如正则、枚举映射）时，
        // 在此 SerializersModule 中注册，避免散落在各处。
        // serializersModule = SerializersModule { }
    }

    /** 将 [config] 序列化为紧凑 JSON 字符串。 */
    fun encode(config: AthenaConfig): String =
        json.encodeToString(AthenaConfig.serializer(), config)

    /**
     * 将 JSON 字符串反序列化为 [AthenaConfig]。
     *
     * @throws kotlinx.serialization.SerializationException 当 JSON 结构非法或必填字段缺失时抛出，
     *         调用方应捕获并回退到默认配置。
     */
    fun decode(jsonStr: String): AthenaConfig =
        json.decodeFromString(AthenaConfig.serializer(), jsonStr)
}

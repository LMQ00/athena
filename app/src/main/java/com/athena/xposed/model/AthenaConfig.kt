package com.athena.xposed.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Athena 配置根容器。
 *
 * JSON 形如：
 * ```json
 * {
 *   "module": { "globalEnabled": true, ... },
 *   "whiteList": [ ... ],
 *   "blackList": [ ... ],
 *   "customEntries": [ ... ]
 * }
 * ```
 *
 * 该类为不可变快照的输入；匹配引擎在 reload 时整体替换持有的
 * `AtomicReference<Snapshot>`，从而实现无锁热更新。
 *
 * 序列化说明：[EntrySet] 通过 [EntrySetSerializer] 自定义编解码，
 * 反序列化后会自动重建包名索引，因此调用方无需手动 reindex。
 * 持久化与跨进程传输统一使用 [com.athena.xposed.data.JsonCodec]；
 * 本伴生对象提供的 [Json] / [fromJson] / [toJson] 仅供引擎与单测
 * 直接使用，配置与 [com.athena.xposed.data.JsonCodec] 保持一致。
 */
@Serializable
data class AthenaConfig(
    /** 全局模块配置 */
    val module: ModuleConfig = ModuleConfig(),

    /** 白名单条目集合（含 IM 保活条目，按 [AppEntry.mode] 区分） */
    val whiteList: EntrySet = EntrySet(),

    /** 黑名单条目集合 */
    val blackList: EntrySet = EntrySet(),

    /** 自定义冻结配置条目集合（不参与分类优先级） */
    val customEntries: EntrySet = EntrySet()
) {
    companion object {
        /** 全模块禁用、所有列表为空的兜底默认配置。 */
        val DEFAULT: AthenaConfig = AthenaConfig(
            module = ModuleConfig(globalEnabled = false)
        )

        /**
         * 共享的 JSON 实例：
         *  - 忽略未知字段，前向兼容旧配置
         *  - 不编码默认值，保持文件精简
         *  - 不使用 prettyPrint，与 [com.athena.xposed.data.JsonCodec] 保持一致
         *
         * 注意：此实例仅供引擎与单测直接使用，持久化/跨进程传输统一走 [JsonCodec]。
         */
        val Json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            prettyPrint = false
        }
    }

    /**
     * 从 JSON 字符串反序列化为 [AthenaConfig]。
     * 请优先使用 [com.athena.xposed.data.JsonCodec.decode] 保证编码一致性。
     */
    @Deprecated("Use JsonCodec.decode() for consistent encoding", ReplaceWith("JsonCodec.decode(json)"))
    fun fromJson(json: String): AthenaConfig =
        Json.decodeFromString(serializer(), json)

    /**
     * 序列化为 JSON 字符串。
     * 请优先使用 [com.athena.xposed.data.JsonCodec.encode] 保证编码一致性。
     */
    @Deprecated("Use JsonCodec.encode() for consistent encoding", ReplaceWith("JsonCodec.encode(this)"))
    fun toJson(): String = Json.encodeToString(serializer(), this)
}

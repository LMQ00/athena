package com.athena.xposed.model

import kotlinx.serialization.Serializable

/**
 * 全局模块配置。
 *
 * 不包含具体应用条目，仅描述模块整体行为开关与默认参数。
 */
@Serializable
data class ModuleConfig(
    /** 模块总开关，false 时引擎整体不生效 */
    val globalEnabled: Boolean = true,

    /** 未命中任何列表时的默认策略 */
    val defaultPolicy: DefaultPolicy = DefaultPolicy.FOLLOW_SYSTEM,

    /** 全局默认冻结超时（毫秒），被 [AppEntry.customFreezeTimeoutMs] 覆盖 */
    val defaultFreezeTimeoutMs: Long = 60_000L,

    /** 全局默认 IM 心跳超时（毫秒），被 [AppEntry.imHeartbeatTimeoutMs] 覆盖 */
    val defaultImHeartbeatTimeoutMs: Long = 120_000L,

    /** 是否输出调试日志 */
    val debugLog: Boolean = false,

    /** 是否启用 native 文件注入（用于 ColorOS 自定义类 hook） */
    val nativeFileInjection: Boolean = false,

    /**
     * 配置 schema 版本，用于未来迁移。当前为 1。
     */
    val schemaVersion: Int = SCHEMA_VERSION
) {
    companion object {
        /** 当前配置 schema 版本 */
        const val SCHEMA_VERSION: Int = 1
    }
}

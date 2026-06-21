package com.athena.xposed.model

import kotlinx.serialization.Serializable

/**
 * 白名单 / 黑名单 / IM 保活 / 自定义冻结配置中的一条应用条目。
 *
 * - [packageName] 是主键；同一 [ProtectionMode] 下不允许重复。
 * - [processNames] 为空时匹配该包名下的所有进程；非空时按进程名精确或正则匹配。
 * - [customFreezeTimeoutMs] 为 null 表示不覆盖全局默认值。
 */
@Serializable
data class AppEntry(
    /** 应用包名，主键 */
    val packageName: String,

    /** 显示用应用名（仅 UI 使用，不参与匹配） */
    val appName: String = "",

    /**
     * 进程名过滤列表。空列表表示匹配该包名下所有进程。
     * 非空时，进程名须命中其中任一条目才视为匹配。
     */
    val processNames: List<String> = emptyList(),

    /** 为 true 时 [processNames] 视为正则表达式，否则做精确匹配 */
    val processRegex: Boolean = false,

    /** 该条目所属保护模式分类 */
    val mode: ProtectionMode,

    /**
     * 自定义冻结超时（毫秒），null 表示不覆盖全局默认。
     * 0 表示「立即冻结」。
     */
    val customFreezeTimeoutMs: Long? = null,

    /**
     * IM 心跳超时（毫秒），[NO_OVERRIDE] 表示不覆盖全局默认。
     * 注意：FreezePolicy 中判断「已设置」使用 `> 0L`。
     */
    val imHeartbeatTimeoutMs: Long = NO_OVERRIDE,

    /** 是否启用，false 时该条目不参与匹配 */
    val enabled: Boolean = true,

    /** 创建时间戳（毫秒） */
    val createdAt: Long = 0L,

    /** 最近一次更新时间戳（毫秒） */
    val updatedAt: Long = 0L,

    /** 用户备注，可空 */
    val note: String? = null
) {
    companion object {
        /**
         * 哨兵值：表示「不覆盖全局默认」。
         * 用于 [imHeartbeatTimeoutMs]。
         * FreezePolicy 中判断「已设置」使用 `> 0L`。
         */
        const val NO_OVERRIDE: Long = -1L
    }

    /**
     * 判定给定 (packageName, processName) 是否命中本条目。
     *
     * - 包名必须精确相等。
     * - 若 [enabled] 为 false，直接返回 false。
     * - [processName] 为 null 或 [processNames] 为空时视为匹配所有进程。
     * - 否则按 [processRegex] 决定精确匹配或正则匹配。
     */
    fun matches(pkg: String, proc: String?): Boolean {
        if (!enabled) return false
        if (packageName != pkg) return false
        if (processNames.isEmpty()) return true
        if (proc == null) return false

        return if (processRegex) {
            processNames.any { pattern ->
                runCatching { Regex(pattern).matches(proc) }.getOrDefault(false)
            }
        } else {
            proc in processNames
        }
    }
}

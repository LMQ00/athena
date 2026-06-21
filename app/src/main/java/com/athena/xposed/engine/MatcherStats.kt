package com.athena.xposed.engine

/**
 * 匹配引擎当前快照的统计信息。
 *
 * 仅用于调试 / UI 展示，不应作为判定依据。
 */
data class MatcherStats(
    /** 白名单（含 IM 保活）条目数 */
    val whiteListSize: Int,

    /** 黑名单条目数 */
    val blackListSize: Int,

    /** 自定义冻结配置条目数 */
    val customConfigSize: Int,

    /** 合并后白名单包名数 */
    val effectiveWhitePkg: Int,

    /** 合并后强制冻结包名数 */
    val effectiveFfPkg: Int,

    /** 合并后 IM 保活包名数 */
    val effectiveImPkg: Int,

    /** 最近一次 reload 的时间戳（毫秒） */
    val lastReloadedAt: Long
)

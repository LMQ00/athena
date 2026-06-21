package com.athena.xposed.model

/**
 * 匹配引擎对一个 (packageName, processName) 查询的判定结果。
 *
 * 调用方应按以下顺序解释：
 *  1. [PROTECTED_KEEPALIVE] / [PROTECTED_CUSTOM]：受保护，拦截 kill。
 *  2. [FORCE_FREEZE]：命中黑名单，放行/主动冻结。
 *  3. [FOLLOW_DEFAULT]：未命中任何列表，按 [DefaultPolicy] 处理。
 *  4. [NOT_FOUND]：模块未启用或快照为空。
 */
enum class ProtectionResult {
    /** 命中 IM 保活列表 */
    PROTECTED_KEEPALIVE,

    /** 命中白名单或自定义保活配置 */
    PROTECTED_CUSTOM,

    /** 命中黑名单，强制冻结 */
    FORCE_FREEZE,

    /** 未命中，回退到全局默认策略 */
    FOLLOW_DEFAULT,

    /** 模块未启用或配置为空 */
    NOT_FOUND
}

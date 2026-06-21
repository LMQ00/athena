package com.athena.xposed.model

/**
 * 单条应用条目的保护模式分类。
 *
 * 优先级顺序（从高到低）：
 *   [IM_KEEPALIVE] > [WHITELIST] > [BLACKLIST]
 *
 * [CUSTOM_FREEZE_CONFIG] 不参与分类优先级，仅用于叠加自定义超时设置。
 */
enum class ProtectionMode {
    /** 白名单：永不冻结 */
    WHITELIST,

    /** 黑名单：强制冻结 */
    BLACKLIST,

    /** IM 类应用保活：仅在心跳超时后才允许冻结 */
    IM_KEEPALIVE,

    /** 自定义冻结配置：仅覆盖冻结/心跳超时，不影响分类判定 */
    CUSTOM_FREEZE_CONFIG
}

package com.athena.xposed.model

/**
 * 全局默认策略：当应用既不在白名单也不在黑名单时使用。
 *
 * @param code 持久化到 SharedPreferences 时的稳定整数编码。
 */
enum class DefaultPolicy(val code: Int) {
    /** 跟随系统默认行为（不干预） */
    FOLLOW_SYSTEM(0),

    /** 强制排除出冻结目标 */
    FORCE_EXCLUDE(1),

    /** 强制冻结 */
    FORCE_FREEZE(2);

    companion object {
        @JvmStatic
        fun fromCode(code: Int): DefaultPolicy =
            entries.firstOrNull { it.code == code } ?: FOLLOW_SYSTEM
    }
}

package com.athena.xposed.engine

import com.athena.xposed.model.AthenaConfig
import com.athena.xposed.model.FreezePolicy
import com.athena.xposed.model.ProtectionResult

/**
 * 策略匹配引擎接口。
 *
 * 实现需保证：
 *  - 线程安全：[classify] / [getFreezePolicy] / [getEffectiveConfig] 可并发调用。
 *  - 热更新：[reload] 切换底层不可变快照，不阻塞读路径。
 */
interface IPolicyMatcher {
    /**
     * 判定指定 (packageName, processName) 的保护结果。
     *
     * @param packageName 应用包名
     * @param processName 进程名，可为 null（表示「任意进程」语义）
     */
    fun classify(packageName: String, processName: String? = null): ProtectionResult

    /**
     * 获取指定包名的定制冻结策略（来自 [com.athena.xposed.model.ProtectionMode.CUSTOM_FREEZE_CONFIG]）。
     * 不存在定制配置时返回 null，由调用方回退到 [getEffectiveConfig]。
     */
    fun getFreezePolicy(packageName: String): FreezePolicy?

    /** 获取全局合并后的有效冻结策略快照。 */
    fun getEffectiveConfig(): FreezePolicy

    /**
     * 热更新配置。整体替换内部不可变快照，对并发读无影响。
     */
    fun reload(config: AthenaConfig)

    /** 返回当前快照的统计信息。 */
    fun stats(): MatcherStats
}

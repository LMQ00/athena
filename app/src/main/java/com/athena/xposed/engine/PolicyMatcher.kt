package com.athena.xposed.engine

import com.athena.xposed.model.AppEntry
import com.athena.xposed.model.AthenaConfig
import com.athena.xposed.model.DefaultPolicy
import com.athena.xposed.model.FreezePolicy
import com.athena.xposed.model.ProtectionMode
import com.athena.xposed.model.ProtectionResult
import java.util.concurrent.atomic.AtomicReference

/**
 * 默认策略匹配引擎实现。
 *
 * 线程安全策略：
 *  - 所有读路径通过 [AtomicReference] 获取一个不可变 [Snapshot]，
 *    在该快照上完成全部判定，期间不持有任何锁。
 *  - [reload] 构造新 [Snapshot] 后用 CAS 整体替换，读路径无感切换。
 *
 * 优先级规则（从高到低）：
 *   IM_KEEPALIVE > WHITELIST > BLACKLIST > [DefaultPolicy]
 *
 * CUSTOM_FREEZE_CONFIG 不参与分类优先级，仅叠加自定义超时设置到
 * 最终 [FreezePolicy] 上。
 */
class PolicyMatcher(
    initial: AthenaConfig = AthenaConfig()
) : IPolicyMatcher {

    /** 不可变快照。所有字段只读，可被多线程并发读取。 */
    private class Snapshot(
        val config: AthenaConfig,
        val effective: FreezePolicy,
        /** packageName -> 自定义冻结策略（仅 CUSTOM_FREEZE_CONFIG 条目） */
        val customPolicies: Map<String, FreezePolicy>,
        val whiteList: List<AppEntry>,
        val blackList: List<AppEntry>,
        val imKeepalive: List<AppEntry>,
        val reloadedAt: Long
    )

    private val ref: AtomicReference<Snapshot> = AtomicReference(buildSnapshot(initial))

    override fun classify(packageName: String, processName: String?): ProtectionResult {
        val snap = ref.get()
        if (!snap.config.module.globalEnabled) return ProtectionResult.NOT_FOUND

        // 1. IM_KEEPALIVE 优先级最高
        if (snap.imKeepalive.any { it.matches(packageName, processName) }) {
            return ProtectionResult.PROTECTED_KEEPALIVE
        }

        // 2. WHITELIST
        if (snap.whiteList.any { it.matches(packageName, processName) }) {
            return ProtectionResult.PROTECTED_CUSTOM
        }

        // 3. BLACKLIST
        if (snap.blackList.any { it.matches(packageName, processName) }) {
            return ProtectionResult.FORCE_FREEZE
        }

        // 4. CUSTOM_FREEZE_CONFIG 不参与分类判定，落入默认策略
        return when (snap.config.module.defaultPolicy) {
            DefaultPolicy.FOLLOW_SYSTEM -> ProtectionResult.FOLLOW_DEFAULT
            DefaultPolicy.FORCE_EXCLUDE -> ProtectionResult.PROTECTED_CUSTOM
            DefaultPolicy.FORCE_FREEZE -> ProtectionResult.FORCE_FREEZE
        }
    }

    override fun getFreezePolicy(packageName: String): FreezePolicy? {
        val snap = ref.get()
        val custom = snap.customPolicies[packageName] ?: return null
        // 将自定义超时叠加到全局有效策略上
        return snap.effective.merge(custom).withSignature()
    }

    override fun getEffectiveConfig(): FreezePolicy = ref.get().effective

    override fun reload(config: AthenaConfig) {
        val next = buildSnapshot(config)
        ref.set(next)
    }

    override fun stats(): MatcherStats {
        val s = ref.get()
        return MatcherStats(
            whiteListSize = s.config.whiteList.size,
            blackListSize = s.config.blackList.size,
            customConfigSize = s.config.customEntries.size,
            effectiveWhitePkg = s.effective.whitePkg.size,
            effectiveFfPkg = s.effective.ffPkg.size,
            effectiveImPkg = s.effective.imPkg.size,
            lastReloadedAt = s.reloadedAt
        )
    }

    // ---- 内部构建 ----------------------------------------------------------

    /**
     * 从 [AthenaConfig] 构建不可变快照，并预计算合并后的 [FreezePolicy]。
     */
    private fun buildSnapshot(config: AthenaConfig): Snapshot {
        val now = System.currentTimeMillis()

        // whiteList 中按 mode 拆分：WHITELIST / IM_KEEPALIVE
        // （设计上 whiteList 容器承载两种保活条目，由 mode 区分）
        val white = config.whiteList.all().filter { it.enabled }
        val im = white.filter { it.mode == ProtectionMode.IM_KEEPALIVE }
        val pureWhite = white.filter { it.mode == ProtectionMode.WHITELIST }

        val black = config.blackList.all().filter { it.enabled }

        // CUSTOM_FREEZE_CONFIG 转为 per-package 超时片段（不引入包名集合）
        val customPolicies = config.customEntries.all()
            .filter { it.enabled && it.mode == ProtectionMode.CUSTOM_FREEZE_CONFIG }
            .associate { entry ->
                entry.packageName to FreezePolicy(
                    ffTimeoutMs = entry.customFreezeTimeoutMs ?: FreezePolicy.DEFAULT_FF_TIMEOUT,
                    imTimeoutMs = if (entry.imHeartbeatTimeoutMs > 0L) entry.imHeartbeatTimeoutMs else FreezePolicy.DEFAULT_IM_TIMEOUT
                )
            }

        val effective = buildEffectiveConfig(
            config = config,
            pureWhite = pureWhite,
            im = im,
            black = black,
            customPolicies = customPolicies
        )

        return Snapshot(
            config = config,
            effective = effective,
            customPolicies = customPolicies,
            whiteList = pureWhite,
            blackList = black,
            imKeepalive = im,
            reloadedAt = now
        )
    }

    /**
     * 按优先级规则构建最终 [FreezePolicy]：
     *   IM_KEEPALIVE > WHITELIST > BLACKLIST > 默认超时
     *
     * 超时使用模块全局默认值，各条目的自定义超时不合并到全局策略中，
     * 而是通过 [getFreezePolicy] 按包名返回独立的 per-package 策略。
     * 这避免了将第一个黑条目的超时错误地全局化，也避免了
     * CUSTOM_FREEZE_CONFIG 的折叠合并丢失 per-package 粒度。
     */
    private fun buildEffectiveConfig(
        config: AthenaConfig,
        pureWhite: List<AppEntry>,
        im: List<AppEntry>,
        black: List<AppEntry>,
        customPolicies: Map<String, FreezePolicy>
    ): FreezePolicy {
        val module = config.module

        return FreezePolicy(
            whitePkg = pureWhite.map { it.packageName }.toSet(),
            ffPkg = black.map { it.packageName }.toSet(),
            ffTimeoutMs = module.defaultFreezeTimeoutMs,
            imPkg = im.map { it.packageName }.toSet(),
            imTimeoutMs = module.defaultImHeartbeatTimeoutMs
        ).withSignature()
    }
}

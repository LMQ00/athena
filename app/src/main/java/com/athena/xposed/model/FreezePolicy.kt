package com.athena.xposed.model

import kotlinx.serialization.Serializable

/**
 * 合并后的最终冻结策略。
 *
 * 该数据类是匹配引擎 `buildEffectiveConfig()` 的产物，描述：
 *  - 哪些包名应被保活（不冻结）
 *  - 哪些包名应被强制冻结
 *  - IM 类包名及其心跳超时
 *  - 默认冻结超时
 *  - 内容签名（用于跨进程一致性校验）
 */
@Serializable
data class FreezePolicy(
    /** 白名单包名集合（永不冻结） */
    val whitePkg: Set<String> = emptySet(),

    /** 强制冻结包名集合 */
    val ffPkg: Set<String> = emptySet(),

    /** 默认冻结超时（毫秒） */
    val ffTimeoutMs: Long = 60_000L,

    /** IM 保活包名集合 */
    val imPkg: Set<String> = emptySet(),

    /** IM 心跳超时（毫秒） */
    val imTimeoutMs: Long = 120_000L,

    /** 内容签名，用于跨进程一致性校验。空字符串表示未计算。 */
    val signature: String = ""
) {
    /**
     * 将另一份策略合并进当前实例，返回新实例（不可变）。
     *
     * 合并规则：
     *  - 包名集合并集
     *  - 超时取「另一份」的非默认值优先，否则保留本份
     *  - 签名合并后置空，需重新计算
     *
     * 用于将 [com.athena.xposed.model.ProtectionMode.CUSTOM_FREEZE_CONFIG]
     * 叠加到主策略之上。
     */
    fun merge(other: FreezePolicy): FreezePolicy {
        val mergedWhite = whitePkg + other.whitePkg
        val mergedFf = ffPkg + other.ffPkg
        val mergedIm = imPkg + other.imPkg
        val mergedFfTimeout =
            if (other.ffTimeoutMs > 0L && other.ffTimeoutMs != DEFAULT_FF_TIMEOUT) other.ffTimeoutMs
            else ffTimeoutMs
        val mergedImTimeout =
            if (other.imTimeoutMs > 0L && other.imTimeoutMs != DEFAULT_IM_TIMEOUT) other.imTimeoutMs
            else imTimeoutMs
        return copy(
            whitePkg = mergedWhite,
            ffPkg = mergedFf,
            imPkg = mergedIm,
            ffTimeoutMs = mergedFfTimeout,
            imTimeoutMs = mergedImTimeout,
            signature = "" // 合并后需重新签名
        )
    }

    /** 计算内容签名（SHA-256 摘要的十六进制前 16 位）。 */
    fun computeSignature(): String {
        val sb = StringBuilder()
        sb.append("w=").append(whitePkg.sorted().joinToString(","))
        sb.append("|ff=").append(ffPkg.sorted().joinToString(","))
        sb.append("|fft=").append(ffTimeoutMs)
        sb.append("|im=").append(imPkg.sorted().joinToString(","))
        sb.append("|imt=").append(imTimeoutMs)
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(sb.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.substring(0, 16)
    }

    /** 返回带签名的副本。 */
    fun withSignature(): FreezePolicy = copy(signature = computeSignature())

    companion object {
        /** 默认冻结超时常量，供 [merge] 判断「非默认值」使用。 */
        const val DEFAULT_FF_TIMEOUT: Long = 60_000L

        /** 默认 IM 心跳超时常量。 */
        const val DEFAULT_IM_TIMEOUT: Long = 120_000L
    }
}

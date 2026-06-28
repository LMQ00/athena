package com.swipeguard.xposed.hook

import android.util.Log
import com.swipeguard.xposed.data.RemoteConfigRepository
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * OFreezer 3.0 运行时白名单查询拦截 Hook。
 *
 * 逆向报告（§6.2 HK07）指出 ColorOS 16 的 OFreezer 在每次 kill 决策前
 * 通过 `g2/e$d` 类的 M/N/P 方法查询「某包名是否在 <whitePkg> 白名单中」。
 * 该 Map<String, Integer> 在系统启动时由 XML 解析构建，后续配置热更新时
 * 不会重新加载。
 *
 * 本 Hook 拦截这些运行时查询方法：当查询的包名在我们的有效白名单中时，
 * 直接返回「在白名单中」的标识值，使 OFreezer 跳过杀进程决策。
 *
 * 工作层级：
 * ```
 * 划卡 → IAthenaService.clearProcess(Bundle)
 *   → h1 (AthenaKillerManagerService)
 *   → g2/e$d.M(pkg)? ← 在此处拦截：返回"在白名单中"
 *     → 是 → 跳过 kill（不触发任何 kill 路径）
 * ```
 *
 * vs SwipeKillHooks 的工作层级：
 * ```
 *   → 否 → r3.c.forceStopPackageAndSaveActivity(pkg)
 *     → OplusActivityManager.forceStopPackage()
 *       → 在此处拦截（SwipeKillHooks）
 * ```
 *
 * 两个拦截层互补：本 Hook 在决策层就让 OFreezer 放弃杀进程；
 * SwipeKillHooks 在执行力层兜底。
 *
 * 容错：类名/方法查找全面 try-catch，所有候选都找不到时仅 WARN 日志降级。
 */
class WhitePkgLookupHooks(
    private val module: XposedModule,
    private val classLoader: ClassLoader,
) {

    @Volatile
    private var effectiveSet: Set<String> = emptySet()
    @Volatile
    private var enabled: Boolean = true

    private val tag = "SwipeGuard/WhitePkgLookup"

    fun syncConfig(repo: RemoteConfigRepository) {
        val cfg = repo.load()
        enabled = cfg.enabled
        effectiveSet = cfg.effectiveProtectedApps
    }

    /**
     * 安装白名单查询拦截 Hook。
     *
     * 尝试多个候选类名以兼容不同 Athena 版本的 ProGuard 混淆。
     * 对每个找到的类，尝试 Hook 其所有符合条件的查询方法。
     */
    fun install() {
        var hookedCount = 0

        for (clz in findCandidateClasses()) {
            hookedCount += hookWhitePkgMethods(clz)
        }

        if (hookedCount > 0) {
            module.log(
                Log.INFO, tag,
                "Install complete: $hookedCount method(s) hooked, " +
                "effectiveSet size=${effectiveSet.size}, enabled=$enabled"
            )
        } else {
            module.log(
                Log.WARN, tag,
                "No whitePkg lookup methods found — runtime whitelist interception " +
                "not available. SwipeKillHooks will be the sole protection layer."
            )
        }
    }

    // ------------------------------------------------------------------
    // 候选类搜索
    // ------------------------------------------------------------------

    /**
     * 查找 `g2/e$d` 类的候选全限定名。
     *
     * `g2/e$d` 在 Java 字节码中为 `com.oplus.athena.g2$e$d`（混淆后的
     * 内部类名）。不同 Athena 版本可能有不同混淆名，故提供多个候选。
     */
    private fun findCandidateClasses(): List<Class<*>> {
        val candidates = listOf(
            // 来自逆向报告 v6.0.1 的确认名
            "com.oplus.athena.g2\$e\$d",
            "oplus.athena.g2\$e\$d",
            // 可能的 alt 混淆名（跨版本兼容）
            "com.oplus.athena.g1\$e\$d",
            "com.oplus.athena.g2\$f\$d",
            "com.oplus.athena.g3\$e\$d",
            "com.oplus.athena.h2\$e\$d",
            // 可能无内部类嵌套（扁平化混淆）
            "com.oplus.athena.G2WhitePkg",
            "com.oplus.athena.ElsaWhitePkg",
        )

        val found = mutableListOf<Class<*>>()
        for (name in candidates) {
            try {
                val clz = Class.forName(name, false, classLoader)
                found.add(clz)
                module.log(Log.DEBUG, tag, "Found class: $name")
            } catch (_: ClassNotFoundException) {
                // 继续尝试下一个
            }
        }
        return found
    }

    // ------------------------------------------------------------------
    // 方法 Hook
    // ------------------------------------------------------------------

    /**
     * Hook 给定类中符合条件的 whitePkg 查询方法。
     *
     * 策略：Hook 所有以下特征的方法：
     * 1. 名称匹配已知的 ProGuard 名（M、N、P）或其变体
     * 2. 接受至少一个 String 参数（包名）
     * 3. 返回 boolean 或 int（表示"是否在白名单"或"白名单 category"）
     *
     * 如果精确名称未找到，fallback 到模糊匹配：
     * - 方法接受一个 String 参数
     * - 返回 boolean 或 int
     * - 排除构造方法和 Object 继承方法
     *
     * @return Hook 成功的方法数量
     */
    private fun hookWhitePkgMethods(clz: Class<*>): Int {
        var hooked = 0

        // 优先匹配已知 ProGuard 名（来源于逆向报告）
        val knownNames = setOf("M", "N", "P")
        val methods = clz.declaredMethods

        // 第一轮：精确匹配已知名称
        for (method in methods.filter { it.name in knownNames }) {
            if (tryHookMethod(clz, method)) hooked++
        }

        // 第二轮：模糊匹配（仅当第一轮未命中足够方法时）
        if (hooked < 1) {
            for (method in methods) {
                // 跳过已 Hook 的方法
                if (method.name in knownNames) continue
                // 跳过构造方法、静态初始化、Object 方法
                if (method.name.startsWith("<")) continue
                if (method.name in setOf("toString", "hashCode", "equals",
                        "getClass", "notify", "notifyAll", "wait")) continue

                // 条件：有 String 参数 + 返回 boolean/int
                val hasStringParam = method.parameterTypes.any { it == String::class.java }
                val returnType = method.returnType
                val isBooleanReturn = returnType == Boolean::class.java ||
                        returnType == Boolean::class.javaPrimitiveType
                val isIntReturn = returnType == Int::class.java ||
                        returnType == Int::class.javaPrimitiveType

                if (hasStringParam && (isBooleanReturn || isIntReturn)) {
                    if (tryHookMethod(clz, method)) hooked++
                }
            }
        }

        if (hooked == 0) {
            module.log(
                Log.WARN, tag,
                "No matching methods found in ${clz.name} (${methods.size} total)"
            )
        }

        return hooked
    }

    /**
     * 尝试 Hook 单个方法。
     *
     * @return true 表示 Hook 成功
     */
    private fun tryHookMethod(clz: Class<*>, method: Method): Boolean {
        return try {
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (!enabled) return@intercept chain.proceed()

                    val pkg = findPackageArg(chain, method)
                    if (pkg != null && pkg in effectiveSet) {
                        module.log(
                            Log.INFO, tag,
                            "Intercepted whitePkg lookup for $pkg (method=${clz.simpleName}.${method.name})"
                        )
                        // 返回"在白名单中"的值
                        return@intercept createWhitelistedResult(method)
                    }

                    chain.proceed()
                }
            module.log(
                Log.DEBUG, tag,
                "Hooked ${clz.name}.${method.name}(${method.parameterTypes.contentToString()}) → ${method.returnType.simpleName}"
            )
            true
        } catch (t: Throwable) {
            module.log(
                Log.WARN, tag,
                "Failed to hook ${clz.name}.${method.name}: ${t.message}"
            )
            false
        }
    }

    // ------------------------------------------------------------------
    // 参数提取与返回值构建
    // ------------------------------------------------------------------

    /**
     * 从方法参数中找到第一个有意义的包名字符串参数。
     * 跳过空字符串、跳过 "android." / "com.android." 前缀的系统包名。
     */
    private fun findPackageArg(
        chain: XposedInterface.Chain,
        method: Method,
    ): String? {
        try {
            for (i in method.parameterTypes.indices) {
                if (method.parameterTypes[i] != String::class.java) continue
                val v = chain.getArg(i) as? String ?: continue
                if (v.isEmpty()) continue
                // 跳过明显的非包名字符串
                if (!v.contains('.')) continue
                if (v.startsWith("android.") || v.startsWith("com.android.")
                    || v.startsWith("java.") || v.startsWith("dalvik.")) continue
                return v
            }
        } catch (_: Throwable) {
        }
        return null
    }

    /**
     * 构建"在白名单中"的返回值。
     *
     * - 如果方法返回 boolean → true（在白名单中）
     * - 如果方法返回 int → 100（category=forcewhite，最高保护等级）
     * - 其他类型 → 放行（调用 proceed）
     */
    private fun createWhitelistedResult(method: Method): Any? {
        val returnType = method.returnType
        return when {
            returnType == Boolean::class.java ||
                    returnType == Boolean::class.javaPrimitiveType -> true  // "是，在白名单中"
            returnType == Int::class.java ||
                    returnType == Int::class.javaPrimitiveType -> 100       // category=forcewhite
            else -> null  // 未知返回类型，由调用方处理
        }
    }
}

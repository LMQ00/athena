package com.swipeguard.xposed.hook

import android.util.Log
import com.swipeguard.xposed.data.RemoteConfigRepository
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field

/**
 * Athena 决策层 + 执行层拦截 Hook（新架构，基于逆向 6.0.1 实际代码路径）。
 *
 * ### 逆向发现
 *
 * 之前的 Hook 策略基于误读的逆向报告，类名和方法都不存在于当前 APK 中。
 * 实际 Athena 6.0.1 的完整 kill 路径如下：
 *
 * ```
 * clearProcess(Bundle) → h1.m → n(action,Bundle)
 *   ├→ "REQUEST_CLEAR_SPEC_APP" (划卡清理)
 *   │    → x3/y (ClearSpecAppAction)
 *   │    → p0.getStopType(ProcDetailInfo, a3/a)  ← ★ 决策层拦截
 *   │      → 返回 0 = 跳过 (不杀)
 *   │      → 返回 2 = 执行 kill via ForceStopStrategy
 *   │        → ForceStopStrategy.e → s.b → s.c
 *   │          → r3/c.h/i → AMS forceStop  ★ 执行层拦截
 *   │
 *   ├→ athenaKill / athenaKill2 (内存压力)
 *   │    → h1.i(level=2) → s.b → s.c → r3/c.h/i → KILL
 *   │    → h1.i(level=1) → s.d → s.e → Process.killProcess → KILL
 *   │
 *   └→ athenaKill3 (批量清理)
 *        → h1.j(level=2) → s.b → s.c → r3/c.h/i → KILL
 *        → h1.j(level=1) → s.d → s.e → Process.killProcess → KILL
 * ```
 *
 * ### Hook 策略（三层防御）
 *
 * 1. **决策层** ([hookGetStopType])：拦截 `p0.getStopTypeInner()`，
 *    在划卡清理的决策阶段就让系统跳过受保护应用。
 *
 * 2. **执行层** ([hookForceStop])：拦截 `s.c()`（最终 force-stop 执行点），
 *    覆盖所有通过 `r3/c.h/i` 调用 AMS forceStop 的路径。
 *
 * 3. **执行层** ([hookProcessKill])：拦截 `s.e()`（最终 Process.killProcess 执行点），
 *    覆盖所有通过 `Process.killProcess` 的直接杀进程路径。
 *
 * 三层防御共同覆盖所有已知 kill 路径。相比旧的 7 路径 Hook 策略，
 * 本实现类名准确、方法参数经过逆向确认、更简洁可靠。
 */
class AthenaDecisionHooks(
    private val module: XposedModule,
    private val classLoader: ClassLoader,
) {

    @Volatile
    private var enabled: Boolean = true
    @Volatile
    private var effectiveSet: Set<String> = emptySet()

    private val tag = "SwipeGuard/Decision"

    /**
     * 从配置仓储同步配置。
     */
    fun syncConfig(repo: RemoteConfigRepository) {
        val cfg = repo.load()
        enabled = cfg.enabled
        effectiveSet = cfg.effectiveProtectedApps
    }

    /**
     * 安装三层防御 Hook。
     */
    fun install() {
        var installed = 0
        var failed = 0

        if (tryInstall("getStopType") { hookGetStopType() }) installed++ else failed++
        if (tryInstall("forceStop(s.c)") { hookForceStop() }) installed++ else failed++
        if (tryInstall("processKill(s.e)") { hookProcessKill() }) installed++ else failed++

        if (failed == 0) {
            module.log(
                Log.INFO, tag,
                "All $installed hooks installed. effectiveSet size=${effectiveSet.size}"
            )
        } else {
            module.log(
                Log.WARN, tag,
                "$installed/$installed+$failed hooks installed. effectiveSet size=${effectiveSet.size}"
            )
        }
    }

    // ------------------------------------------------------------------
    // Layer 1: 决策层 - 拦截 getStopTypeInner
    // ------------------------------------------------------------------

    /**
     * Hook [p0.getStopTypeInner] — 划卡清理的决策点。
     *
     * 当 [ProcDetailInfo.pkgName] 在有效白名单中时，返回 0（跳过处理）。
     *
     * 目标签名：
     * ```java
     * int p0.getStopTypeInner(com.oplus.app.athena.ProcDetailInfo, a3.a)
     * ```
     *
     * 返回值语义：
     * - 0 = 跳过该进程（受保护）
     * - 1 = 保护但不跳过（已记录在 KeepRecord 中）
     * - 2 = 执行 kill
     */
    private fun hookGetStopType() {
        try {
            val p0Class = Class.forName(
                "com.oplus.athena.common.parser.athena.p0",
                false, classLoader
            )

            // 通过方法名 + 参数个数匹配，避免查找 a3.a 类
            val method = p0Class.declaredMethods.firstOrNull { m ->
                m.name == "getStopTypeInner" && m.parameterCount == 2
            }

            if (method == null) {
                module.log(Log.WARN, tag, "getStopTypeInner not found in p0, skip")
                return
            }

            // 预获取 ProcDetailInfo.pkgName 字段
            val procDetailClass = Class.forName(
                "com.oplus.app.athena.ProcDetailInfo",
                false, classLoader
            )
            val pkgNameField: Field = procDetailClass.getDeclaredField("pkgName")
            pkgNameField.isAccessible = true

            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (!enabled) return@intercept chain.proceed()

                    val arg0 = chain.getArg(0)
                    if (arg0 == null || !procDetailClass.isInstance(arg0)) {
                        return@intercept chain.proceed()
                    }

                    val pkg = try {
                        pkgNameField.get(arg0) as? String
                    } catch (_: Throwable) {
                        null
                    }

                    if (pkg != null && pkg in effectiveSet) {
                        module.log(
                            Log.INFO, tag,
                            "getStopTypeInner: intercept $pkg → SKIP(0)"
                        )
                        return@intercept 0 // 0 = 跳过进程
                    }

                    chain.proceed()
                }

            module.log(Log.INFO, tag, "Hook installed: p0.getStopTypeInner")
        } catch (t: Throwable) {
            throw RuntimeException("getStopTypeInner hook failed", t)
        }
    }

    // ------------------------------------------------------------------
    // Layer 2: 执行层 - 拦截 s.c() force-stop 调用
    // ------------------------------------------------------------------

    /**
     * Hook [s.c] — 所有 force-stop 类型 kill 的最终执行点。
     *
     * 被以下路径调用：
     * - [h1.i] (athenaKill/athenaKill2, level=2)
     * - [h1.j] (athenaKill3, level=2)
     * - [ForceStopStrategy.e] (划卡清理)
     *
     * 目标签名：
     * ```java
     * static void s.c(Context, String pkgName, int userId,
     *                 int reason, int subReason,
     *                 String callerNote, String callerPkg, boolean forceStopWithUser)
     * ```
     *
     * 第二个参数为包名，提取后与有效白名单比对。
     */
    private fun hookForceStop() {
        try {
            val utilsClass = Class.forName(
                "com.oplus.athena.systemservice.utils.s",
                false, classLoader
            )

            val method = utilsClass.declaredMethods.firstOrNull { m ->
                m.name == "c" && m.parameterCount >= 3 &&
                    m.parameterTypes[1] == String::class.java // pkgName 在 index 1
            }

            if (method == null) {
                module.log(Log.WARN, tag, "s.c not found, skip forceStop hook")
                return
            }

            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (!enabled) return@intercept chain.proceed()

                    val pkg = chain.getArg(1) as? String
                    if (pkg != null && pkg in effectiveSet) {
                        module.log(
                            Log.INFO, tag,
                            "s.c: blocked force-stop for $pkg"
                        )
                        return@intercept null // void 方法，返回 null = 跳过
                    }

                    chain.proceed()
                }

            module.log(Log.INFO, tag, "Hook installed: s.c (force-stop)")
        } catch (t: Throwable) {
            throw RuntimeException("s.c hook failed", t)
        }
    }

    // ------------------------------------------------------------------
    // Layer 3: 执行层 - 拦截 s.e() Process.killProcess 调用
    // ------------------------------------------------------------------

    /**
     * Hook [s.e] — 所有通过 Process.killProcess 杀进程的最终执行点。
     *
     * 被以下路径调用：
     * - [h1.i] (athenaKill/athenaKill2, level=1)
     * - [h1.j] (athenaKill3, level=1)
     *
     * 目标签名：
     * ```java
     * static boolean s.e(int pid, int uid, String pkgName,
     *                    int userId, int reason, int subReason,
     *                    String callerNote, String callerPkg,
     *                    Callable preAction, Callable postAction)
     * ```
     *
     * 第三个参数 (index=2) 为包名。
     */
    private fun hookProcessKill() {
        try {
            val utilsClass = Class.forName(
                "com.oplus.athena.systemservice.utils.s",
                false, classLoader
            )

            val method = utilsClass.declaredMethods.firstOrNull { m ->
                m.name == "e" && m.parameterCount >= 3 &&
                    m.parameterTypes[2] == String::class.java // pkgName 在 index 2
            }

            if (method == null) {
                module.log(Log.WARN, tag, "s.e not found, skip processKill hook")
                return
            }

            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (!enabled) return@intercept chain.proceed()

                    val pkg = chain.getArg(2) as? String
                    if (pkg != null && pkg in effectiveSet) {
                        module.log(
                            Log.INFO, tag,
                            "s.e: blocked Process.killProcess for $pkg"
                        )
                        return@intercept false // 返回 false = 未执行 kill
                    }

                    chain.proceed()
                }

            module.log(Log.INFO, tag, "Hook installed: s.e (Process.killProcess)")
        } catch (t: Throwable) {
            throw RuntimeException("s.e hook failed", t)
        }
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    /**
     * 统一 try-catch 安装辅助。
     */
    private fun tryInstall(name: String, block: () -> Unit): Boolean {
        return try {
            block()
            true
        } catch (t: Throwable) {
            module.log(Log.ERROR, tag, "$name install failed: ${t.message}")
            false
        }
    }

    /** 判断包名是否受保护。 */
    private fun shouldProtect(pkg: String?): Boolean =
        pkg != null && enabled && pkg in effectiveSet
}

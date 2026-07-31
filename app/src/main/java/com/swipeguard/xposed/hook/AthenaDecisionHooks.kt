package com.swipeguard.xposed.hook

import android.util.Log
import com.swipeguard.xposed.data.RemoteConfigRepository
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field

/**
 * Athena 决策层 + 执行层拦截 Hook（基于 MT MCP 五路并行逆向 Athena 6.0.1，2026-07-31）。
 *
 * ### 逆向确认的 kill 路径（全部经 smali 行号验证）
 *
 * ```
 * IAthenaService$Stub.onTransact code 224(0xe0) = clearProcess
 *   → RemoteService.clearProcess → h1.m(Bundle) → h1.n(action,Bundle)
 *     → "oplus.intent.action.REQUEST_CLEAR_SPEC_APP" (划卡清理)
 *       → x3/y (ClearSpecAppAction).z0 → P0 → y0
 *         → p0.getStopType(ProcDetailInfo, a3/a)  ← ★ 决策层拦截
 *           → 返回 0 = 跳过（不杀）→ log "skip process" + 记 keep
 *           → 返回 1 = killProcess 路径 → B0 → s.d → s.e → Process.killProcess
 *           → 返回 2 = force-stop 路径 → B0 → s.b → s.c → r3/c.h/i → AMS forceStop
 *     → athenaKill/athenaKill2 → h1.i；athenaKill3 → h1.j（无决策层，仅执行层兜底）
 *
 * 注意：ForceStopStrategy.e 属 Osense 外部清理链（externalclear），
 * 不是划卡路径——划卡由 x3/y.B0 直接分派 s.b/s.d。
 * ```
 *
 * ### Hook 策略（三层防御）
 *
 * 1. **决策层** ([hookGetStopType])：拦截 `p0.getStopTypeInner()`，
 *    在划卡清理的决策阶段就让系统跳过受保护应用（返回 0 = 原生"跳过/保留"语义，
 *    全部 9 个调用点一致解释，勿改返回 1——x3/f1/x3/y 会把 1 当作处理类型送入 kill 流）。
 *
 * 2. **执行层** ([hookForceStop])：拦截 `s.c()`（最终 force-stop 执行点，
 *    全 dex 唯一调用 OplusActivityManager.forceStopPackageAndSaveActivity 反射的入口），
 *    覆盖所有 force-stop 类 kill 路径。
 *
 * 3. **执行层** ([hookProcessKill])：拦截 `s.e()`（最终 Process.killProcess 执行点，
 *    全 dex 除 OplusPinnerService 自杀外唯一的 killProcess 调用点），
 *    覆盖所有直接杀进程路径。
 *
 * 三层防御共同覆盖所有已知 kill 路径。类加载使用 [ClassFinders] 多 ClassLoader
 * 兜底——Athena APK 类随 OplusAthenaSystemService（process="system"）运行在
 * system_server，但其 dex 可能不在 system_server 主 ClassLoader 链上。
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
     * 目标签名（MT MCP dex_names 确认，无混淆重名）：
     * ```java
     * int p0.getStopTypeInner(com.oplus.app.athena.ProcDetailInfo, a3.a)
     * ```
     *
     * 返回值语义（smali 逐行验证）：
     * - 0 = 跳过该进程（原生默认值；mSysResControlList/mKillOrProtectMap/
     *       mAuthScopeProtectList 命中、boot 限制等场景原生就返回 0）
     * - 1 = persist/受保护（mUseKillMap=false、y2/d.b 硬编码表等）
     * - 2 = 执行 kill（isForceStopApp、a3.a/a3.c 返回 2）
     *
     * 白名单命中返回 0 即“跳过”，与原生语义一致。
     */
    private fun hookGetStopType() {
        try {
            val p0Class = ClassFinders.findClass(
                "com.oplus.athena.common.parser.athena.p0",
                classLoader
            )
            if (p0Class == null) {
                module.log(Log.ERROR, tag, "p0 class not found — decision hook DISABLED")
                return
            }

            // 通过方法名 + 参数个数匹配，避免查找 a3.a 类
            val method = p0Class.declaredMethods.firstOrNull { m ->
                m.name == "getStopTypeInner" && m.parameterCount == 2
            }

            if (method == null) {
                module.log(Log.WARN, tag, "getStopTypeInner not found in p0, skip")
                return
            }

            // 预获取 ProcDetailInfo.pkgName 字段
            val procDetailClass = ClassFinders.findClass(
                "com.oplus.app.athena.ProcDetailInfo",
                classLoader
            )
            if (procDetailClass == null) {
                module.log(Log.ERROR, tag, "ProcDetailInfo class not found — decision hook DISABLED")
                return
            }
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

            // 双保险：同时 hook getStopType(ProcDetailInfo, a3/a) 2-arg 重载。
            // 当前版本该重载只是 getStopTypeInner 的包装（2-arg → 3-arg null List → inner），
            // 单一 inner hook 已覆盖全部调用点；此处防御未来 OTA 中 getStopType
            // 不再经 inner 的实现漂移（D1 建议）。白名单命中同样返回 0。
            val wrapperMethod = p0Class.declaredMethods.firstOrNull { m ->
                m.name == "getStopType" && m.parameterCount == 2
            }
            if (wrapperMethod != null) {
                module.hook(wrapperMethod)
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
                            return@intercept 0
                        }
                        chain.proceed()
                    }
                module.log(Log.INFO, tag, "Hook installed: p0.getStopType(2-arg) (double insurance)")
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
     * 被以下路径调用（MT MCP 2026-07-31 确认，s.c 调用点共 4 处）：
     * - [a4/t] O0/P0/i1（清除引擎直调）
     * - [x3/m] f（深度清理）
     * - [x3/s0] I0
     * - [s.b]（薄包装，被 h1.i/h1.j/ForceStopStrategy.e/x3/* 共 12 处调用）
     *
     * 目标签名（smali L127 确认）：
     * ```java
     * static void s.c(Context, String pkgName, int userId,
     *                 int reason, int eventId,
     *                 String note, String subNote, boolean delayed)
     * ```
     *
     * 第二个参数为包名，提取后与有效白名单比对。
     * 内部：p7==false → r3/c.h（OplusActivityManager.forceStopPackageAndSaveActivity 反射）；
     *       p7==true  → r3/c.i（延迟 2000ms，transact/i.forceStopPackageAsUser）。
     */
    private fun hookForceStop() {
        try {
            val utilsClass = ClassFinders.findClass(
                "com.oplus.athena.systemservice.utils.s",
                classLoader
            )
            if (utilsClass == null) {
                module.log(Log.ERROR, tag, "utils.s class not found — forceStop hook DISABLED")
                return
            }

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
     * 被以下路径调用（MT MCP 2026-07-31 确认，s.e 调用点共 5 处）：
     * - [a4/t] O0（L4360/L4586）、P0（L5066）、i1（L7590）
     * - [s.d]（薄包装，被 h1.i/h1.j level==1 分支、x3 系列调用）
     *
     * 目标签名（smali L351 确认）：
     * ```java
     * static boolean s.e(int pid, int uid, String pkgName,
     *                    int reason, int level, int eventId,
     *                    String note, String subNote,
     *                    Callable preAction, Callable postAction)
     * ```
     *
     * 第三个参数 (index=2) 为包名。内部 L425 调 Process.killProcess(pid)，
     * L427 调 s.f → L590 killProcessGroup(uid,pid)。
     * 全 dex 除 OplusPinnerService 自杀外，这是唯一的 Process.killProcess 调用点。
     */
    private fun hookProcessKill() {
        try {
            val utilsClass = ClassFinders.findClass(
                "com.oplus.athena.systemservice.utils.s",
                classLoader
            )
            if (utilsClass == null) {
                module.log(Log.ERROR, tag, "utils.s class not found — processKill hook DISABLED")
                return
            }

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

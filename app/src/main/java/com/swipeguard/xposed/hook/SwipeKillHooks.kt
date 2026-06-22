package com.swipeguard.xposed.hook

import android.util.Log
import com.swipeguard.xposed.data.RemoteConfigRepository
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * 划卡杀进程拦截 Hook。
 *
 * 拦截 [ActivityManagerService] 及 Athena 的杀进程方法。
 * 当被杀的包名在 SwipeGuard 白名单中时，跳过 kill。
 *
 * 三路径保护：
 * 1. killBackgroundProcesses(String, int, int) — AOSP 标准划卡杀路径
 * 2. forceStopPackage(String, int) — ColorOS / OEM 备用杀路径
 * 3. com.oplus.athena.*.forceStopPackageAndSaveActivity(String, int) —
 *    Athena 实际杀进程执行点（绕过 AMS，通过 OplusActivityManager 直杀）
 *
 * 路径 1/2 拦截常规 AMS kill；路径 3 拦截 Athena 专有杀路径（ColorOS 16 划卡杀进程的真实路径）。
 *
 * 参数：pkg 直接是包名，无需反查 uid → 简单可靠。
 */
class SwipeKillHooks(private val module: XposedModule,
                      private val classLoader: ClassLoader) {

    @Volatile
    private var effectiveSet: Set<String> = emptySet()
    @Volatile
    private var enabled: Boolean = true
    private val tag = "SwipeGuard/SwipeKill"

    /** 从配置仓储同步配置，计算有效白名单 */
    fun syncConfig(repo: RemoteConfigRepository) {
        val cfg = repo.load()
        enabled = cfg.enabled
        effectiveSet = (repo.loadSystemDefaults() - cfg.userRemovals) + cfg.userAdditions
    }

    /** 安装 Hook（三路径） */
    fun install() {
        hookKillBackgroundProcesses()
        hookForceStopPackage()
        hookForceStopPackageAndSaveActivity()
    }

    /**
     * 路径 1: ActivityManagerService.killBackgroundProcesses
     * 标椎划卡杀路径，被杀包名直接传进来。
     */
    private fun hookKillBackgroundProcesses() {
        try {
            val amsClass = Class.forName("com.android.server.am.ActivityManagerService", false, classLoader)
            val method = amsClass.declaredMethods.firstOrNull { m ->
                m.name == "killBackgroundProcesses" &&
                m.parameterCount >= 1 &&
                m.parameterTypes[0] == String::class.java
            } ?: run {
                module.log(Log.WARN, tag, "killBackgroundProcesses not found, skip")
                return
            }

            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val pkg = chain.getArg(0) as? String
                    if (shouldProtect(pkg)) {
                        module.log(Log.INFO, tag, "Blocked killBackgroundProcesses for $pkg")
                        return@intercept null
                    }
                    chain.proceed()
                }

            module.log(Log.INFO, tag, "Hook installed: killBackgroundProcesses")
        } catch (t: Throwable) {
            module.log(Log.ERROR, tag, "killBackgroundProcesses hook failed: ${t.message}")
        }
    }

    /**
     * 路径 2: ActivityManagerService.forceStopPackage
     * ColorOS 等 OEM 有时用此路径替代 killBackgroundProcesses。
     */
    private fun hookForceStopPackage() {
        try {
            val amsClass = Class.forName("com.android.server.am.ActivityManagerService", false, classLoader)
            val method = amsClass.declaredMethods.firstOrNull { m ->
                m.name == "forceStopPackage" &&
                m.parameterCount >= 1 &&
                m.parameterTypes[0] == String::class.java
            } ?: run {
                module.log(Log.WARN, tag, "forceStopPackage not found, skip")
                return
            }

            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val pkg = chain.getArg(0) as? String
                    if (shouldProtect(pkg)) {
                        module.log(Log.INFO, tag, "Blocked forceStopPackage for $pkg")
                        return@intercept null
                    }
                    chain.proceed()
                }

            module.log(Log.INFO, tag, "Hook installed: forceStopPackage")
        } catch (t: Throwable) {
            module.log(Log.ERROR, tag, "forceStopPackage hook failed: ${t.message}")
        }
    }

    /**
     * 路径 3: com.oplus.athena.r3.c.forceStopPackageAndSaveActivity
     *
     * Athena 实际杀进程执行点（绕过 AOSP AMS，直接通过 OplusActivityManager）。
     * 这是 ColorOS 16 上划卡杀进程的真实 kill 路径。
     *
     * 逆向报告显示调用链：
     *   r3.c.forceStopPackageAndSaveActivity(pkg, userId)
     *     → i3.h (OplusActivityManager 薄封装)
     *     → android.app.OplusActivityManager.forceStopPackage()
     *     → x3.d.killProcess()
     *
     * 类名 `r3.c` 为 ProGuard 混淆名，实际在 system_server 的 ClassLoader
     * 中以 `com.oplus.athena.r3.c` 或类似路径存在。
     * 尝试多个候选名以兼容不同 Athena 版本。
     *
     * 容错：找不到类/方法时仅 WARN 日志，不影响路径 1/2。
     */
    private fun hookForceStopPackageAndSaveActivity() {
        try {
            // 尝试多个混淆类名（兼容不同 Athena 版本/混淆配置）
            // 逆向报告: r3.c 是 OplusActivityManager 封装类
            val classCandidates = listOf(
                "com.oplus.athena.r3.c",
                "com.oplus.athena.r3.d",
                "oplus.athena.r3.c"
            )
            val clz = classCandidates.firstNotNullOfOrNull { name ->
                try {
                    Class.forName(name, false, classLoader)
                } catch (_: ClassNotFoundException) {
                    null
                }
            }

            if (clz == null) {
                module.log(
                    Log.WARN, tag,
                    "Athena OplusWrapper class not found, skip forceStopPackageAndSaveActivity"
                )
                return
            }

            val methods = clz.declaredMethods.filter { m ->
                m.name == "forceStopPackageAndSaveActivity" &&
                    m.parameterCount >= 1 &&
                    m.parameterTypes[0] == String::class.java
            }
            if (methods.isEmpty()) {
                module.log(
                    Log.WARN, tag,
                    "forceStopPackageAndSaveActivity not found in ${clz.name}, skip"
                )
                return
            }

            // 可能有多重重载，全部 hook 以确保拦截
            for (method in methods) {
                module.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val pkg = chain.getArg(0) as? String
                        if (shouldProtect(pkg)) {
                            module.log(
                                Log.INFO, tag,
                                "Blocked forceStopPackageAndSaveActivity for $pkg"
                            )
                            return@intercept null
                        }
                        chain.proceed()
                    }
            }

            module.log(
                Log.INFO, tag,
                "Hook installed: ${clz.name}.forceStopPackageAndSaveActivity" +
                    " (${methods.size} overload(s))"
            )
        } catch (t: Throwable) {
            module.log(
                Log.ERROR, tag,
                "forceStopPackageAndSaveActivity hook failed: ${t.message}"
            )
        }
    }

    /** 判断包名是否受保护。 */
    private fun shouldProtect(pkg: String?): Boolean =
        pkg != null && enabled && pkg in effectiveSet
}

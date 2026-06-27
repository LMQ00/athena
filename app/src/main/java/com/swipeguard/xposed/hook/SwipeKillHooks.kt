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

    /** 从配置仓储同步配置，计算有效白名单（systemDefaults 已在 config JSON 中） */
    fun syncConfig(repo: RemoteConfigRepository) {
        val cfg = repo.load()
        enabled = cfg.enabled
        effectiveSet = cfg.effectiveProtectedApps
    }

    /** 安装 Hook（五路径） */
    fun install() {
        hookKillBackgroundProcesses()
        hookForceStopPackage()
        hookForceStopPackageAndSaveActivity()
        hookOplusActivityManagerForceStop()
        hookKillProcess()
        hookProcessKillProcess()
        module.log(
            Log.INFO, tag,
            "Install complete. effectiveSet size=${effectiveSet.size}, enabled=$enabled"
        )
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
                "r3.c",
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

    /**
     * 路径 4: android.app.OplusActivityManager.forceStopPackage
     *
     * ColorOS 16 划卡杀进程的真实路径（Athena 绕过 AOSP AMS，通过此方法执行）。
     * 逆向报告显示完整调用链：
     *   r3.c.forceStopPackageAndSaveActivity(pkg, userId)
     *     → i3.h → OplusActivityManager.forceStopPackage(pkg, userId)
     *       → x3.d.killProcess()
     *
     * OplusActivityManager 是 Android 框架类（android.app 包），
     * 不会因 Athena APK 混淆变化而失效，是最可靠的拦截点。
     */
    private fun hookOplusActivityManagerForceStop() {
        try {
            val clazz = Class.forName("android.app.OplusActivityManager", false, classLoader)
            val methods = clazz.declaredMethods.filter { m ->
                m.name == "forceStopPackage" &&
                m.parameterCount >= 1 &&
                m.parameterTypes[0] == String::class.java
            }
            if (methods.isEmpty()) {
                module.log(Log.WARN, tag, "OplusActivityManager.forceStopPackage not found, skip")
                return
            }
            for (method in methods) {
                module.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val pkg = chain.getArg(0) as? String
                        if (shouldProtect(pkg)) {
                            module.log(
                                Log.INFO, tag,
                                "Blocked OplusActivityManager.forceStopPackage for $pkg"
                            )
                            return@intercept null
                        }
                        chain.proceed()
                    }
            }
            module.log(
                Log.INFO, tag,
                "Hook installed: android.app.OplusActivityManager.forceStopPackage" +
                " (${methods.size} overload(s))"
            )
        } catch (t: Throwable) {
            module.log(Log.WARN, tag, "OplusActivityManager hook failed: ${t.message}")
        }
    }

    /**
     * 路径 5: x3.d.killProcess — 最终杀进程执行点
     *
     * 逆向报告显示调用链：
     *   r3.c.forceStopPackageAndSaveActivity(pkg, userId)
     *     → i3.h → OplusActivityManager.forceStopPackage()
     *     → x3.d.killProcess(clearInfo, pid, ...)  ← 所有 kill 路径的最终汇合点
     *     → Process.killProcess()
     *
     * x3.d 是 ProGuard 混淆名，尝试多个候选以兼容不同 Athena 版本。
     * 从 killProcess 的参数中提取包名（通常通过 clearInfo 字符串）。
     */
    private fun hookKillProcess() {
        try {
            val classCandidates = listOf(
                "x3.d",
                "com.oplus.athena.x3.d",
                "oplus.athena.x3.d",
                "com.oplus.athena.x3.e",
                "com.oplus.athena.x4.d",
            )
            val clz = classCandidates.firstNotNullOfOrNull { name ->
                try {
                    Class.forName(name, false, classLoader)
                } catch (_: ClassNotFoundException) {
                    null
                }
            }

            if (clz == null) {
                module.log(Log.WARN, tag, "x3.d class not found, skip killProcess hook")
                return
            }

            val methods = clz.declaredMethods.filter { m ->
                m.name == "killProcess" && m.parameterCount >= 1
            }
            if (methods.isEmpty()) {
                module.log(Log.WARN, tag, "killProcess not found in ${clz.name}, skip")
                return
            }

            for (method in methods) {
                module.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val pkg = extractPkgFromKillProcess(chain)
                        if (shouldProtect(pkg)) {
                            module.log(Log.INFO, tag, "Blocked killProcess for $pkg")
                            return@intercept null
                        }
                        chain.proceed()
                    }
            }
            module.log(
                Log.INFO, tag,
                "Hook installed: ${clz.name}.killProcess (${methods.size} overload(s))"
            )
        } catch (t: Throwable) {
            module.log(Log.WARN, tag, "killProcess hook failed: ${t.message}")
        }
    }

    /**
     * 从 killProcess 的参数中提取包名。
     * x3.d.killProcess 的典型签名：killProcess(String clearInfo, int pid, ...)
     * clearInfo 格式可能有多种变体：
     *   - "pkgName:reason"（冒号分割）
     *   - "kill clearInfo=pkgName:reason"（带前缀 + 等号）
     *   - "clearInfo=pkgName:reason"（等号 + 冒号）
     *   - 直接包名
     * 遍历所有 String 参数，用多种启发式尝试提取包名。
     */
    private fun extractPkgFromKillProcess(chain: XposedInterface.Chain): String? {
        try {
            val args = chain.getArgs() ?: return null
            for (arg in args) {
                val s = arg as? String ?: continue
                if (s.isEmpty()) continue

                // 收集所有候选字符串，优先级：等号后 > 原始串
                // 格式 "clearInfo=pkgName:reason" 中等号后的才是包名前缀
                val candidates = mutableListOf<String>()

                // 格式: "kill clearInfo=pkgName:reason" 或 "clearInfo=pkgName:reason"
                // 等号后面的内容优先级更高（去掉前缀后的实际参数）
                val eqIdx = s.indexOf('=')
                if (eqIdx >= 0 && eqIdx < s.length - 1) {
                    val afterEq = s.substring(eqIdx + 1).trim()
                    candidates.add(afterEq)
                }
                // 原始字符串整体（作为 fallback）
                candidates.add(s)

                for (candidate in candidates) {
                    // 先尝试冒号分割（pkgName:reason 或 pkgName:reason:subReason）
                    val colonIdx = candidate.indexOf(':')
                    val beforeColon = if (colonIdx > 0) candidate.substring(0, colonIdx) else candidate
                    // 空格分割（kill clearInfo=... → 取最后一段）
                    val parts = beforeColon.split(' ')
                    for (part in parts) {
                        val trimmed = part.trim()
                        if (trimmed.isEmpty()) continue
                        if ("." in trimmed && !trimmed.startsWith("android.")) {
                            return trimmed
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return null
    }

    /**
     * 路径 6: android.os.Process.killProcess — 最终防线
     *
     * 逆向报告显示 x3.d.killProcess 最终调用 Process.killProcess(pid)。
     * 如果所有上游 Hook 都未拦截到（类名变化、混淆更新），
     * 此 Hook 作为最后一道防线。
     *
     * 局限：Process.killProcess(int pid) 只接收 pid 不接收包名，
     * 需要通过 /proc/pid/cmdline 反查包名。
     */
    private fun hookProcessKillProcess() {
        try {
            val processClass = Class.forName("android.os.Process", false, classLoader)
            val method = processClass.getDeclaredMethod(
                "killProcess", Int::class.javaPrimitiveType
            )
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val pid = chain.getArg(0) as Int
                    if (pid <= 0) return@intercept chain.proceed()
                    val pkg = getPkgByPid(pid)
                    if (shouldProtect(pkg)) {
                        module.log(
                            Log.INFO, tag,
                            "Blocked Process.killProcess for $pkg (pid=$pid)"
                        )
                        return@intercept null
                    }
                    chain.proceed()
                }
            module.log(Log.INFO, tag, "Hook installed: Process.killProcess")
        } catch (t: Throwable) {
            module.log(Log.WARN, tag, "Process.killProcess hook failed: ${t.message}")
        }
    }

    /** 通过 pid 读取 /proc/pid/cmdline 反查包名。 */
    private fun getPkgByPid(pid: Int): String? {
        try {
            val cmdline = java.io.File("/proc/$pid/cmdline")
                .readBytes()
                .toString(Charsets.UTF_8)
                .trim('\u0000')
                .trim()
            if (cmdline.isNotEmpty() && "." in cmdline) return cmdline
        } catch (_: Throwable) {
        }
        return null
    }

    /** 判断包名是否受保护。 */
    private fun shouldProtect(pkg: String?): Boolean =
        pkg != null && enabled && pkg in effectiveSet
}

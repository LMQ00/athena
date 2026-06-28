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
 * 七路径保护：
 * 1. killBackgroundProcesses(String, ...) — AOSP 标准划卡杀路径
 * 2. forceStopPackage(String, ...) — ColorOS / OEM 备用杀路径
 * 3. com.oplus.athena.*.forceStopPackageAndSaveActivity(String, ...) —
 *    Athena OplusActivityManager 封装（绕过 AMS）
 * 4. android.app.OplusActivityManager.forceStopPackage(String, ...) —
 *    框架 API 层拦截（最可靠，不随混淆变化）
 * 5. com.oplus.athena.*.killProcess(String clearInfo, ...) —
 *    所有 kill 路径的最终汇合点
 * 6. com.oplus.athena.*.killProcessGroup(int uid, ...) —
 *    cgroup 级别杀进程（HK03，防止绕过 Java 层）
 * 7. android.os.Process.killProcess(int pid) —
 *    最终防线（通过 PID 反查包名）
 *
 * 参数：路径 1-4 直接传包名，路径 5-6 需启发式提取，路径 7 需 PID 反查。
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

    /** 安装 Hook（七路径） */
    fun install() {
        hookKillBackgroundProcesses()
        hookForceStopPackage()
        hookForceStopPackageAndSaveActivity()
        hookOplusActivityManagerForceStop()
        hookKillProcess()
        hookKillProcessGroup()
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
     * 容错：找不到类/方法时仅 WARN 日志（但路径 4 和路径 5 可能同基类可达）。
     */
    private fun hookForceStopPackageAndSaveActivity() {
        try {
            // 尝试多个混淆类名（兼容不同 Athena 版本/混淆配置）
            // 逆向报告: r3.c 是 OplusActivityManager 封装类
            // 注意：必须使用全限定名（com.oplus.athena.r3.c），
            // 简单名（"r3.c"）Class.forName 不会找到
            val classCandidates = listOf(
                "com.oplus.athena.r3.c",
                "com.oplus.athena.r3.d",
                "com.oplus.athena.r4.c",
                "com.oplus.athena.r2.c",
                "com.oplus.athena.r3.b",
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
     * 路径 5: com.oplus.athena.x3.d.killProcess — 最终杀进程执行点
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
                "com.oplus.athena.x3.d",
                "com.oplus.athena.x3.e",
                "com.oplus.athena.x4.d",
                "com.oplus.athena.x2.d",
                "com.oplus.athena.x3.f",
                "com.oplus.athena.h3.d",
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
     *
     * x3.d.killProcess 的典型签名：killProcess(String clearInfo, int pid, ...)
     * clearInfo 格式可能有多种变体：
     *   - "pkgName:reason"（冒号分割）
     *   - "kill clearInfo=pkgName:reason"（带前缀 + 等号）
     *   - "clearInfo=pkgName:reason"（等号 + 冒号）
     *   - "kill clearInfo=pkgName"（无冒号）
     *   - 直接包名
     *
     * 使用正则表达式模式匹配提高鲁棒性：
     *   1. 匹配等号后、冒号前的包名（优先）
     *   2. 匹配形如 xxx:reason 中的包名
     *   3. 匹配包名模式（a.b.c）
     */
    private fun extractPkgFromKillProcess(chain: XposedInterface.Chain): String? {
        try {
            val args = chain.getArgs() ?: return null
            for (arg in args) {
                val s = arg as? String ?: continue
                if (s.isEmpty()) continue

                // 策略 1: 等号后提取（clearInfo=pkgName:reason 或 clearInfo=pkgName）
                val eqMatch = EQ_PKG_REGEX.find(s)
                if (eqMatch != null) {
                    val pkg = eqMatch.groupValues[1]
                    if (isValidPackageName(pkg)) return pkg
                }

                // 策略 2: 冒号前提取（pkgName:reason）
                val colonMatch = COLON_PKG_REGEX.find(s)
                if (colonMatch != null) {
                    val pkg = colonMatch.groupValues[1]
                    if (isValidPackageName(pkg)) return pkg
                }

                // 策略 3: 直接匹配包名模式（a.b.c 至少两段）
                val directMatch = DIRECT_PKG_REGEX.find(s)
                if (directMatch != null) {
                    val pkg = directMatch.groupValues[1]
                    if (isValidPackageName(pkg)) return pkg
                }

                // 策略 4（兜底）: 对每个空格分隔的词做包名判断
                // 用于 "kill clearInfo=pkgName:reason" 这种格式
                for (word in s.split(' ')) {
                    val trimmed = word.trim()
                    if (isValidPackageName(trimmed)) return trimmed
                }
            }
        } catch (_: Throwable) {
        }
        return null
    }

    companion object {
        // 从 "key=pkgName:reason" 类型的字符串中提取 pkgName
        internal val EQ_PKG_REGEX = Regex("""=\s*([a-zA-Z_][\w.]*[a-zA-Z\w])\s*:""")

        // 从 "pkgName:reason" 类型的字符串中提取 pkgName
        internal val COLON_PKG_REGEX = Regex("""^([a-zA-Z_][\w.]*[a-zA-Z\w])\s*:""")

        // 直接匹配标准包名格式（至少两段，如 com.example.app）
        internal val DIRECT_PKG_REGEX = Regex("""([a-zA-Z_][\w.]*\.[a-zA-Z_][\w.]+)""")
    }

    /**
     * 判断字符串是否为有效的 Android 包名。
     * 规则：至少包含一个点，不以 "android." 或 "java." 开头，
     * 不包含空格/控制字符。
     */
    private fun isValidPackageName(s: String): Boolean {
        if (s.length < 3 || s.length > 255) return false
        if (!s.contains('.')) return false
        if (s.startsWith("android.") || s.startsWith("java.") ||
            s.startsWith("dalvik.") || s.startsWith("com.android.")) return false
        // 排除 IP 地址、文件路径等非包名
        if (s.matches(Regex("""\d+(\.\d+)+\s*"""))) return false  // IP 地址
        if (s.startsWith("/") || s.startsWith("\\")) return false  // 文件路径
        if (s.contains(' ')) return false  // 含空格
        return true
    }

    /**
     * 路径 6: com.oplus.athena.x3.d.killProcessGroup — cgroup 级别杀进程
     *
     * 逆向报告（§6.1 HK03）指出 x3.d 除了 killProcess 还有 killProcessGroup 方法。
     * cgroup 级别的杀进程绕过 Java 层 kill 拦截，直接从内核层面终止进程组。
     *
     * 本 Hook 作为 SwipeKillHooks 的补充，阻止以下 bypass 路径：
     * ```
     * 内存压力 → x3.d.killProcessGroup(uid, pid, ...) → cgroup kill
     *   ↳ 不经过 killBackgroundProcesses / forceStopPackage → 绕过路径 1-5
     * ```
     *
     * 从 killProcessGroup 的参数中提取包名较为困难（通常只有 uid/pid），
     * 因此使用 pid → /proc/pid/cmdline 反查。
     * 参数有 int uid, int pid 时优先用 pid 反查；只有 int pid 时直接反查。
     */
    private fun hookKillProcessGroup() {
        try {
            val classCandidates = listOf(
                "com.oplus.athena.x3.d",
                "com.oplus.athena.x3.e",
                "com.oplus.athena.x4.d",
                "com.oplus.athena.x2.d",
                "com.oplus.athena.h3.d",
            )
            val clz = classCandidates.firstNotNullOfOrNull { name ->
                try {
                    Class.forName(name, false, classLoader)
                } catch (_: ClassNotFoundException) {
                    null
                }
            }

            if (clz == null) {
                module.log(Log.WARN, tag, "x3.d class not found, skip killProcessGroup hook")
                return
            }

            val methods = clz.declaredMethods.filter { m ->
                m.name == "killProcessGroup" && m.parameterCount >= 1
            }
            if (methods.isEmpty()) {
                module.log(Log.WARN, tag, "killProcessGroup not found in ${clz.name}, skip")
                return
            }

            for (method in methods) {
                module.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val pkg = extractPkgFromKillProcessGroup(chain)
                        if (shouldProtect(pkg)) {
                            module.log(
                                Log.INFO, tag,
                                "Blocked killProcessGroup for $pkg"
                            )
                            return@intercept null
                        }
                        chain.proceed()
                    }
            }
            module.log(
                Log.INFO, tag,
                "Hook installed: ${clz.name}.killProcessGroup (${methods.size} overload(s))"
            )
        } catch (t: Throwable) {
            module.log(Log.WARN, tag, "killProcessGroup hook failed: ${t.message}")
        }
    }

    /**
     * 从 killProcessGroup 的参数中提取包名。
     * 典型签名：killProcessGroup(int uid, int pid, ...)
     * 或 killProcessGroup(int pid, ...)
     *
     * 由于参数不含包名，只能通过 pid → /proc/pid/cmdline 反查。
     */
    private fun extractPkgFromKillProcessGroup(chain: XposedInterface.Chain): String? {
        try {
            val args = chain.getArgs() ?: return null
            // 尝试从所有 int 参数中找 pid（非 uid，不是 0-100000 范围的）
            var pid: Int? = null
            var uid: Int? = null
            for (arg in args) {
                when (arg) {
                    is Int -> {
                        if (arg in 100000..999999) {
                            uid = arg  // uid 通常在 100000+ 范围
                        } else if (arg > 0 && arg < 100000) {
                            pid = arg  // pid 通常在 1-32768 范围
                        }
                    }
                }
            }
            pid?.let { return getPkgByPid(it) }
        } catch (_: Throwable) {
        }
        return null
    }

    /**
     * 路径 7: android.os.Process.killProcess — 最终防线
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

    /**
     * 通过 pid 读取 /proc/pid/cmdline 反查包名。
     *
     * 容错：
     * - 进程已死（文件不存在）：返回 null，放行 kill
     * - cmdline 被清空：读取 cmdline 文件失败时使用
     *   /proc/pid/status 中的 Name 字段作为 fallback
     * - 竞争条件：在 system_server 上下文中，目标进程被杀时
     *   cmdline 可能已被清空 → 安全返回 null，不阻止 kill
     *
     * 使用 LRU 缓存减少重复读取同 pid 的开销。
     */
    private val pidCache = object : LinkedHashMap<Int, String?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String?>): Boolean =
            size > 64
    }

    private fun getPkgByPid(pid: Int): String? {
        // 检查缓存
        synchronized(pidCache) {
            pidCache[pid]?.let { return it }
        }
        val result = try {
            readPkgByPid(pid)
        } catch (_: Throwable) {
            null
        }
        synchronized(pidCache) {
            pidCache[pid] = result
        }
        return result
    }

    /** 实际的 PID 反查实现。 */
    private fun readPkgByPid(pid: Int): String? {
        // 方法 1: /proc/pid/cmdline
        try {
            val cmdline = java.io.File("/proc/$pid/cmdline").readBytes()
            val str = cmdline.toString(Charsets.UTF_8)
                .trim('\u0000')
                .trim()
            if (str.isNotEmpty() && isValidPackageName(str)) return str
        } catch (_: Exception) {
        }

        // 方法 2（fallback）: 遍历 /proc/pid/status 找 Name 字段
        // 部分 native 进程改名后可能在此暴露原始包名
        try {
            val status = java.io.File("/proc/$pid/status").readText()
            val nameLine = status.lines().firstOrNull { it.startsWith("Name:") } ?: return null
            val name = nameLine.removePrefix("Name:").trim()
            if (name.isNotEmpty() && isValidPackageName(name)) return name
        } catch (_: Exception) {
        }

        return null
    }

    /** 判断包名是否受保护。 */
    private fun shouldProtect(pkg: String?): Boolean =
        pkg != null && enabled && pkg in effectiveSet
}

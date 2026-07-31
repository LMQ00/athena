package com.swipeguard.xposed.hook

import android.util.Log

/**
 * 共享的 Class 查找工具。
 *
 * ### 为什么需要多 ClassLoader 兜底
 *
 * 逆向确认：Athena 的 kill 决策/执行链（`p0`/`s`/`h1`/`r3`/`x3` 等混淆类）随
 * `OplusAthenaSystemService` 运行在 **system_server** 进程（manifest
 * `android:process="system"`），但它们的 dex 来自 Athena APK。system_server 的
 * 主 ClassLoader（`param.classLoader`）的 parent 链是 bootclasspath，
 * **不一定包含 Athena APK 的 PathClassLoader**——这会导致
 * `Class.forName(name, false, param.classLoader)` 抛 `ClassNotFoundException`，
 * 而 hook 静默失败、白名单完全失效（仅有 WARN 日志）。
 *
 * 因此查找策略按可靠性降序尝试：
 * 1. 传入的 `param.classLoader`（LSPosed 若已将系统 APK 合并进 classpath 则一次命中）
 * 2. `ClassLoader.getSystemClassLoader()` 及其 parent 链
 * 3. 当前线程 `contextClassLoader` 及其 parent 链
 * 4. **进程内所有存活线程**的 `contextClassLoader` 及其 parent 链——
 *    Athena 服务线程（如 h1 的 `athena_service` HandlerThread）的 loader
 *    可能就是 Athena APK 的 PathClassLoader
 *
 * 每次命中即返回，全部失败返回 null。调用方负责记录明确诊断日志。
 */
object ClassFinders {

    private const val TAG = "SwipeGuard/ClassFinders"

    /**
     * 在多个 ClassLoader 中查找类。
     *
     * @param name 全限定类名
     * @param primary 首选 ClassLoader（通常是 system_server 的 param.classLoader）
     * @return 找到的 [Class]，全部失败返回 null
     */
    fun findClass(name: String, primary: ClassLoader?): Class<*>? {
        val seen = HashSet<ClassLoader>()

        fun tryLoad(cl: ClassLoader?): Class<*>? {
            var cur = cl
            while (cur != null) {
                if (!seen.add(cur)) return null
                try {
                    return Class.forName(name, false, cur)
                } catch (_: ClassNotFoundException) {
                    // 继续向上找 parent
                } catch (_: Throwable) {
                    return null // LinkageError 等：此 loader 不可用，放弃
                }
                cur = cur.parent
            }
            return null
        }

        // 1. primary
        tryLoad(primary)?.let { return it }

        // 2. system classloader
        try {
            tryLoad(ClassLoader.getSystemClassLoader())?.let { return it }
        } catch (_: Throwable) {
        }

        // 3. 当前线程 contextClassLoader
        try {
            tryLoad(Thread.currentThread().contextClassLoader)?.let { return it }
        } catch (_: Throwable) {
        }

        // 4. 所有存活线程的 contextClassLoader
        try {
            for (t in Thread.getAllStackTraces().keys) {
                try {
                    tryLoad(t.contextClassLoader)?.let { return it }
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }

        Log.w(TAG, "Class not found via any ClassLoader: $name")
        return null
    }
}

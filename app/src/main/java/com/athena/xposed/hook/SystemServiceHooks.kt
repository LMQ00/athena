package com.athena.xposed.hook

import android.util.Log
import com.athena.xposed.engine.IPolicyMatcher
import com.athena.xposed.model.ProtectionResult
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * AOSP 系统服务的**辅助保活** Hook。
 *
 * 与 [OplusConfigHooks] 不同，本组 Hook **不干预 OFreezer 冻结行为**，
 * 仅在 LMK（Low Memory Killer）回收阶段为白名单进程争取存活机会：
 *
 *  - Hook `OomAdjuster.applyOomAdjLocked(...)` 与
 *    `ProcessList.updateOomAdjLocked(...)`：在系统重新计算 oom_score_adj
 *    之后，对命中 [ProtectionResult.PROTECTED_KEEPALIVE] /
 *    [ProtectionResult.PROTECTED_CUSTOM] 的进程，强制将 adj 设为 `-17`
 *    （system 级别），并通过 `android.os.Process.setOomAdj` 写入内核，
 *    降低被 LMK 选中杀死的概率。
 *
 * 设计要点：
 *  - **辅助路径**：即便本组 Hook 全部失败，OFreezer 冻结路径仍由
 *    [OplusConfigHooks] 单独保障，不依赖此处。
 *  - **反射容错**：AOSP 内部类名 / 方法签名随 Android 版本变动较大，
 *    全部通过反射按名称匹配，找不到则记录后跳过。
 *  - **绝不崩溃**：所有反射与 setOomAdj 调用均 try-catch；PROTECTIVE
 *    异常模式 + 二次 try-catch 双重保险，避免 system_server 因 adj 写入
 *    异常被拖垮。
 */
object SystemServiceHooks {

    private const val TAG = "Athena/SysSvc"

    /** 系统服务级别的 oom_score_adj，作为白名单进程的目标值。 */
    private const val PROTECTED_OOM_ADJ: Int = -17

    /**
     * 安装全部 AOSP 系统服务辅助 Hook。
     *
     * @param module     [ModuleMain] 实例，提供 `hook()` / `log()` 能力
     * @param engine     策略匹配引擎
     * @param classLoader system_server ClassLoader
     * @param handles    安装成功的 Hook 句柄追加到此列表
     */
    fun install(
        module: XposedModule,
        engine: IPolicyMatcher,
        classLoader: ClassLoader,
        handles: MutableList<XposedInterface.HookHandle>,
    ) {
        installOomAdjusterHook(module, engine, classLoader, handles)
        installProcessListHook(module, engine, classLoader, handles)
    }

    // ------------------------------------------------------------------
    // Hook 1: OomAdjuster.applyOomAdjLocked
    // ------------------------------------------------------------------

    /**
     * Hook `com.android.server.am.OomAdjuster.applyOomAdjLocked(...)`。
     *
     * AOSP 中该方法存在多个重载（参数数量 1~6 不等，随版本演进）。此处按
     * 方法名匹配，Hook 所有声明版本；统一在 `proceed()` 之后从第一个
     * `ProcessRecord` 参数提取包名判定保护状态。
     */
    private fun installOomAdjusterHook(
        module: XposedModule,
        engine: IPolicyMatcher,
        classLoader: ClassLoader,
        handles: MutableList<XposedInterface.HookHandle>,
    ) {
        val clazz = findClass(classLoader, "com.android.server.am.OomAdjuster") ?: run {
            module.log(Log.WARN, TAG, "OomAdjuster not found; skip applyOomAdjLocked hook.")
            return
        }
        val processRecordClass = findClass(classLoader, "com.android.server.am.ProcessRecord") ?: run {
            module.log(Log.WARN, TAG, "ProcessRecord not found; skip OomAdjuster hook.")
            return
        }

        val targets = collectMethodsByName(clazz, "applyOomAdjLocked", processRecordClass)
        if (targets.isEmpty()) {
            module.log(Log.WARN, TAG, "No applyOomAdjLocked overload with ProcessRecord found.")
            return
        }

        var hooked = 0
        for (m in targets) {
            val h = module.hook(m)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val pr = chain.args.firstOrNull { it != null && processRecordClass.isInstance(it) }
                        if (pr != null) {
                            enforceProtectedAdj(module, engine, pr)
                        }
                    } catch (t: Throwable) {
                        module.log(Log.ERROR, TAG, "OomAdjuster post-proceed failed.", t)
                    }
                    result
                }
            handles.add(h)
            hooked++
        }
        module.log(Log.INFO, TAG, "Hooked OomAdjuster.applyOomAdjLocked ($hooked overload(s)).")
    }

    // ------------------------------------------------------------------
    // Hook 2: ProcessList.updateOomAdjLocked
    // ------------------------------------------------------------------

    /**
     * Hook `com.android.server.am.ProcessList.updateOomAdjLocked(...)`。
     *
     * 同样按名称匹配多个重载，在 `proceed()` 之后强制白名单进程 adj。
     * 与 [installOomAdjusterHook] 互补：部分 ColorOS 版本仅走 ProcessList
     * 路径更新 adj。
     */
    private fun installProcessListHook(
        module: XposedModule,
        engine: IPolicyMatcher,
        classLoader: ClassLoader,
        handles: MutableList<XposedInterface.HookHandle>,
    ) {
        val clazz = findClass(classLoader, "com.android.server.am.ProcessList") ?: run {
            module.log(Log.WARN, TAG, "ProcessList not found; skip updateOomAdjLocked hook.")
            return
        }
        val processRecordClass = findClass(classLoader, "com.android.server.am.ProcessRecord") ?: run {
            module.log(Log.WARN, TAG, "ProcessRecord not found; skip ProcessList hook.")
            return
        }

        val targets = collectMethodsByName(clazz, "updateOomAdjLocked", processRecordClass)
        if (targets.isEmpty()) {
            module.log(Log.WARN, TAG, "No updateOomAdjLocked overload with ProcessRecord found.")
            return
        }

        var hooked = 0
        for (m in targets) {
            val h = module.hook(m)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val pr = chain.args.firstOrNull { it != null && processRecordClass.isInstance(it) }
                        if (pr != null) {
                            enforceProtectedAdj(module, engine, pr)
                        }
                    } catch (t: Throwable) {
                        module.log(Log.ERROR, TAG, "ProcessList post-proceed failed.", t)
                    }
                    result
                }
            handles.add(h)
            hooked++
        }
        module.log(Log.INFO, TAG, "Hooked ProcessList.updateOomAdjLocked ($hooked overload(s)).")
    }

    // ------------------------------------------------------------------
    // 公共工具
    // ------------------------------------------------------------------

    /**
     * 对单个 [ProcessRecord] 实例执行保护：
     *  1. 反射读取 `packageName` 与 `pid` 字段；
     *  2. 调用 [IPolicyMatcher.classify] 判定保护状态；
     *  3. 命中保活 / 白名单时通过 `android.os.Process.setOomAdj` 写入
     *     [PROTECTED_OOM_ADJ] 到内核。
     *
     * 全程 try-catch；任何反射 / 调用失败仅记录，不影响 adj 主流程。
     */
    private fun enforceProtectedAdj(
        module: XposedModule,
        engine: IPolicyMatcher,
        processRecord: Any,
    ) {
        val pkg: String = readStringField(processRecord, "packageName") ?: return
        if (pkg.isEmpty()) return

        val result = try {
            engine.classify(pkg, processName = null)
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "classify($pkg) failed: ${t.message}")
            return
        }

        if (result != ProtectionResult.PROTECTED_KEEPALIVE &&
            result != ProtectionResult.PROTECTED_CUSTOM
        ) {
            return // 非保护对象，不干预
        }

        val pid: Int = readIntField(processRecord, "pid") ?: return
        if (pid <= 0) return

        try {
            setOomAdj(pid, PROTECTED_OOM_ADJ)
            module.log(Log.DEBUG, TAG, "Forced oom_adj=$PROTECTED_OOM_ADJ for $pkg pid=$pid ($result).")
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "setOomAdj($pid) failed: ${t.message}")
        }
    }

    /**
     * 反射调用 `android.os.Process.setOomAdj(int, int)` 写入内核 oom_score_adj。
     * 该方法为隐藏 API，system_server 进程内可访问。
     */
    @Volatile
    private var setOomAdjMethod: Method? = null
    private fun setOomAdj(pid: Int, adj: Int) {
        var m = setOomAdjMethod
        if (m == null) {
            m = try {
                Class.forName("android.os.Process")
                    .getDeclaredMethod("setOomAdj", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }
            } catch (t: Throwable) {
                // 反射失败时清空缓存，避免持续使用 null
                setOomAdjMethod = null
                throw t
            }
            setOomAdjMethod = m
        }
        m.invoke(null, pid, adj)
    }

    // ---- 反射小工具 ----------------------------------------------------

    private fun findClass(classLoader: ClassLoader, name: String): Class<*>? =
        try {
            Class.forName(name, false, classLoader)
        } catch (_: Throwable) {
            null
        }

    /**
     * 在 [clazz] 中收集名为 [name] 且至少有一个参数类型为 [markerParam] 的方法。
     * 用于跨版本匹配重载方法（如 applyOomAdjLocked 的多种签名）。
     */
    private fun collectMethodsByName(
        clazz: Class<*>,
        name: String,
        markerParam: Class<*>,
    ): List<Method> = try {
        clazz.declaredMethods.filter { method ->
            method.name == name &&
                method.parameterTypes.any { markerParam.isAssignableFrom(it) || it == markerParam }
        }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun readStringField(obj: Any, fieldName: String): String? = try {
        val f = findField(obj.javaClass, fieldName) ?: return null
        f.isAccessible = true
        (f.get(obj) as? String)
    } catch (_: Throwable) {
        null
    }

    private fun readIntField(obj: Any, fieldName: String): Int? = try {
        val f = findField(obj.javaClass, fieldName) ?: return null
        f.isAccessible = true
        when (val v = f.get(obj)) {
            is Int -> v
            is Number -> v.toInt()
            else -> null
        }
    } catch (_: Throwable) {
        null
    }

    /** 沿继承链向上查找字段（兼容 ProcessRecord 字段位于父类的情况）。 */
    private fun findField(clazz: Class<*>, name: String): Field? {
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            try {
                return c.getDeclaredField(name)
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
    }
}

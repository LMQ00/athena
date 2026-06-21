package com.swipeguard.xposed.hook

import android.content.Context
import android.util.Log
import com.swipeguard.xposed.model.SwipeGuardConfig
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.io.File
import java.io.FileInputStream

/**
 * ColorOS OFreezer 3.0 策略读取 Hook。
 *
 * 调研结论（t1）：ColorOS 16 后台管理以 cgroup freezer **冻结**而非 kill 为核心，
 * 关键干预路径有两条，均通过拦截「策略文件读取」实现：
 *
 *  1. **自启动白名单**：`OplusSettings.readConfig(Context, fileName, defaultResId)`
 *     读取 `startup/autostart_white_list.txt`。拦截后追加模块白名单包名，
 *     使白名单内应用免于启动期冻结。
 *
 *  2. **冻结策略 XML**：系统通过 `FileInputStream` 读取
 *     `/data/oplus/os/bpm/sys_elsa_config_list.xml`。拦截后将原始 XML 经
 *     [XmlPolicyBuilder.buildEnhancedXml] 注入 whitePkg 覆盖后返回，
 *     从而在不修改磁盘文件的前提下动态改写冻结策略。
 *
 * 容错：每个 Hook 点独立 try-catch；类/方法查找失败（非 ColorOS 或版本差异）
 * 仅记录日志后跳过，绝不抛出导致 system_server 崩溃。
 *
 * 线程安全：[hijackedStreams] 用 [java.util.WeakHashMap] + 自身同步锁保护，
 * 与 [XmlPolicyBuilder]（无状态）配合，可被 system_server 多线程并发调用。
 * [config] 由调用方 ([ModuleMain]) 保证可见性（热更新时整体替换引用）。
 *
 * @param module    [ModuleMain] 实例，提供 `hook()` / `log()` 等 [XposedInterface] 能力
 * @param config    SwipeGuard 配置快照（线程安全由调用方保证，Hook 闭包按调用时机读取）
 * @param classLoader system_server ClassLoader，用于查找 OplusSettings
 */
object OplusConfigHooks {

    private const val TAG = "SwipeGuard/OplusCfg"

    /** 自启动白名单文件名（相对路径，readConfig 第二参数）。 */
    private const val AUTOSTART_WHITELIST_FILE = "startup/autostart_white_list.txt"

    /** 冻结策略 XML 的绝对路径。 */
    private const val ELSA_CONFIG_PATH = "/data/oplus/os/bpm/sys_elsa_config_list.xml"

    /**
     * 被「劫持」的 FileInputStream → 增强后字节缓冲。
     *
     * 使用 [java.util.WeakHashMap] 避免泄漏已关闭的流对象。同步访问通过
     * [hijackedStreams] 自身作为锁。
     */
    private val hijackedStreams: java.util.WeakHashMap<FileInputStream, ByteArray> =
        java.util.WeakHashMap()

    /**
     * 每个 FileInputStream 当前的读取游标，用于分多次 `read()` 调用时正确切片。
     * 与 [hijackedStreams] 同步管理。
     */
    private val streamCursor: java.util.WeakHashMap<FileInputStream, Int> =
        java.util.WeakHashMap()

    /**
     * mark() 保存的游标位置，用于 mark/reset 支持。
     * 与 [hijackedStreams] 同步管理。
     */
    private val streamMark: java.util.WeakHashMap<FileInputStream, Int> =
        java.util.WeakHashMap()

    /**
     * 安装全部 ColorOS 策略读取 Hook。可被多次调用（重复 install 会注册多个
     * Hook，调用方需自行控制；当前仅 [ModuleMain.onSystemServerStarting] 调用一次）。
     *
     * @param config  当前 SwipeGuard 配置快照；Hook 闭包捕获此引用，
     *                热更新由 [ModuleMain] 整体替换 [config] 实现
     * @param handles 安装成功的 Hook 句柄将追加到此列表，便于统一卸载 / hot-reload
     */
    fun install(
        module: XposedModule,
        config: SwipeGuardConfig,
        classLoader: ClassLoader,
        handles: MutableList<XposedInterface.HookHandle>,
    ) {
        installOplusSettingsReadConfig(module, config, classLoader, handles)
        installElsaConfigFileInputStream(module, config, handles)
    }

    // ------------------------------------------------------------------
    // Hook 1: OplusSettings.readConfig
    // ------------------------------------------------------------------

    /**
     * Hook `OplusSettings.readConfig(Context, String, int)`。
     *
     * 当 `fileName == "startup/autostart_white_list.txt"` 时，先放行原始调用拿到
     * 系统白名单内容（通常为 String），再用 [XmlPolicyBuilder.buildEnhancedAutoStartWhiteList]
     * 追加模块白名单包名后替换返回值。
     *
     * ColorOS 不同版本该类可能位于 `android.provider.OplusSettings` 或
     * `com.oplus.settings.OplusSettings`，逐个尝试。
     */
    private fun installOplusSettingsReadConfig(
        module: XposedModule,
        config: SwipeGuardConfig,
        classLoader: ClassLoader,
        handles: MutableList<XposedInterface.HookHandle>,
    ) {
        val clazz: Class<*>? = findOplusSettingsClass(classLoader)
        if (clazz == null) {
            module.log(Log.WARN, TAG, "OplusSettings class not found; skip readConfig hook.")
            return
        }

        // 候选签名：readConfig(Context, String, int)
        val method = try {
            clazz.getDeclaredMethod(
                "readConfig",
                Context::class.java,
                String::class.java,
                Int::class.javaPrimitiveType
            )
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "OplusSettings.readConfig not found: ${t.message}")
            return
        }

        val handle = module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                // 默认放行原始逻辑
                val result = chain.proceed()
                try {
                    val fileName = chain.getArg(1) as? String
                    if (fileName == AUTOSTART_WHITELIST_FILE && result is String) {
                        if (!config.enabled || config.protectedApps.isEmpty()) {
                            // 模块禁用或白名单为空时无需改写，避免无谓字符串拼接
                            return@intercept result
                        }
                        val enhanced = XmlPolicyBuilder.buildEnhancedAutoStartWhiteList(
                            originalContent = result,
                            config = config
                        )
                        module.log(
                            Log.DEBUG, TAG,
                            "readConfig($fileName) injected ${config.protectedApps.size} white pkgs."
                        )
                        return@intercept enhanced
                    }
                } catch (t: Throwable) {
                    // 注入失败：保留系统原始返回值，不影响启动流程
                    module.log(Log.ERROR, TAG, "readConfig injection failed, fallback.", t)
                }
                // 非 autostart 白名单 / 非 String 返回 / 注入异常 → 透传原结果
                result
            }
        handles.add(handle)
        module.log(Log.INFO, TAG, "Hooked OplusSettings.readConfig on ${clazz.name}.")
    }

    /** 依次尝试 ColorOS 不同包路径下的 OplusSettings 类。 */
    private fun findOplusSettingsClass(classLoader: ClassLoader): Class<*>? {
        val candidates = listOf(
            "android.provider.OplusSettings",
            "com.oplus.settings.OplusSettings",
            "com.android.internal.util.OplusSettings",
        )
        for (name in candidates) {
            try {
                return Class.forName(name, false, classLoader)
            } catch (_: Throwable) {
                // 继续尝试下一个候选
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // Hook 2: FileInputStream 读取 sys_elsa_config_list.xml
    // ------------------------------------------------------------------

    /**
     * Hook `FileInputStream` 的构造与读取，对 [ELSA_CONFIG_PATH] 的读取返回
     * 经 [XmlPolicyBuilder.buildEnhancedXml] 改写后的字节。
     *
     * 实现策略：
     *  1. Hook `FileInputStream(String)` / `FileInputStream(File)` 构造方法。
     *     在原始构造 `proceed()` 之后检测路径：若为目标 XML，则用该流一次性
     *     `readBytes()` 读取原始内容，构建增强字节，并以 `this` 为键存入
     *     [hijackedStreams]。
     *  2. Hook `read(byte[], int, int)` 与单字节 `read()`：若当前流存在于
     *     [hijackedStreams]，从增强缓冲切片返回；否则透传 `proceed()`。
     *
     * 该方式不写磁盘、不依赖文件系统权限，且仅在目标文件流上产生额外开销，
     * 其它 FileInputStream 读写只多一次 WeakHashMap 查询（命不中即放行）。
     *
     * 递归安全：构造 Hook 内调用 `fis.readBytes()` 会触发 `read(...)` Hook，
     * 但此时 [hijackedStreams] 尚未登记该流，故 `read` Hook 直接 `proceed()`，
     * 不会无限递归。
     */
    private fun installElsaConfigFileInputStream(
        module: XposedModule,
        config: SwipeGuardConfig,
        handles: MutableList<XposedInterface.HookHandle>,
    ) {
        val fisClass = FileInputStream::class.java

        // ---- 构造方法：String / File ---------------------------------------
        val ctors = listOfNotNull(
            runCatching { fisClass.getConstructor(String::class.java) }.getOrNull(),
            runCatching { fisClass.getConstructor(File::class.java) }.getOrNull(),
        )
        if (ctors.isEmpty()) {
            module.log(Log.WARN, TAG, "FileInputStream ctors not accessible; skip elsa hook.")
            return
        }

        for (ctor in ctors) {
            val h = module.hook(ctor)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    // 先让真实构造执行（打开底层 fd），再决定是否劫持
                    chain.proceed()
                    try {
                        val fis = chain.thisObject as? FileInputStream ?: return@intercept null
                        val arg = chain.getArg(0)
                        val path = when (arg) {
                            is String -> arg
                            is File -> arg.absolutePath
                            else -> null
                        }
                        // 直接精确匹配路径，避免 contains 子串误判
                        if (path != ELSA_CONFIG_PATH) return@intercept null
                        hijackStream(module, config, fis)
                    } catch (t: Throwable) {
                        module.log(Log.ERROR, TAG, "FileInputStream ctor hijack failed.", t)
                    }
                    // 构造方法返回值被框架忽略
                    null
                }
            handles.add(h)
        }

        // ---- read(byte[], int, int) ---------------------------------------
        val read3 = runCatching {
            fisClass.getMethod(
                "read",
                ByteArray::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
        }.getOrNull()
        if (read3 != null) {
            val h = module.hook(read3)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val fis = chain.thisObject as? FileInputStream
                        ?: return@intercept chain.proceed()
                    val buf = chain.getArg(0) as? ByteArray
                        ?: return@intercept chain.proceed()
                    val off = chain.getArg(1) as Int
                    val len = chain.getArg(2) as Int
                    val served = serveHijacked(fis, buf, off, len)
                    if (served != null) served else chain.proceed()
                }
            handles.add(h)
        }

        // ---- read() 单字节 ------------------------------------------------
        val read0 = runCatching { fisClass.getMethod("read") }.getOrNull()
        if (read0 != null) {
            val h = module.hook(read0)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val fis = chain.thisObject as? FileInputStream
                        ?: return@intercept chain.proceed()
                    val one = ByteArray(1)
                    val n = serveHijacked(fis, one, 0, 1)
                    if (n == null) {
                        chain.proceed()
                    } else if (n <= 0) {
                        -1
                    } else {
                        one[0].toInt() and 0xFF
                    }
                }
            handles.add(h)
        }

        // ---- available() --------------------------------------------------
        val availableMethod = runCatching { fisClass.getMethod("available") }.getOrNull()
        if (availableMethod != null) {
            val h = module.hook(availableMethod)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val fis = chain.thisObject as? FileInputStream
                        ?: return@intercept chain.proceed()
                    val avail = serveAvailable(fis)
                    if (avail != null) avail else chain.proceed()
                }
            handles.add(h)
        }

        // ---- skip(long) ---------------------------------------------------
        val skipMethod = runCatching { fisClass.getMethod("skip", Long::class.javaPrimitiveType) }.getOrNull()
        if (skipMethod != null) {
            val h = module.hook(skipMethod)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val fis = chain.thisObject as? FileInputStream
                        ?: return@intercept chain.proceed()
                    val n = chain.getArg(0) as Long
                    val skipped = serveSkip(fis, n)
                    if (skipped != null) skipped else chain.proceed()
                }
            handles.add(h)
        }

        // ---- mark(int) ----------------------------------------------------
        val markMethod = runCatching { fisClass.getMethod("mark", Int::class.javaPrimitiveType) }.getOrNull()
        if (markMethod != null) {
            val h = module.hook(markMethod)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val fis = chain.thisObject as? FileInputStream
                        ?: return@intercept chain.proceed()
                    if (!serveMark(fis)) chain.proceed()
                }
            handles.add(h)
        }

        // ---- reset() ------------------------------------------------------
        val resetMethod = runCatching { fisClass.getMethod("reset") }.getOrNull()
        if (resetMethod != null) {
            val h = module.hook(resetMethod)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val fis = chain.thisObject as? FileInputStream
                        ?: return@intercept chain.proceed()
                    if (!serveReset(fis)) chain.proceed()
                }
            handles.add(h)
        }

        // ---- markSupported() ----------------------------------------------
        val markSupportedMethod = runCatching { fisClass.getMethod("markSupported") }.getOrNull()
        if (markSupportedMethod != null) {
            val h = module.hook(markSupportedMethod)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val fis = chain.thisObject as? FileInputStream
                        ?: return@intercept chain.proceed()
                    val supported = serveMarkSupported(fis)
                    if (supported != null) supported else chain.proceed()
                }
            handles.add(h)
        }

        // ---- close() 清理劫持状态 -----------------------------------------
        val closeMethod = runCatching { fisClass.getMethod("close") }.getOrNull()
        if (closeMethod != null) {
            val h = module.hook(closeMethod)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val fis = chain.thisObject as? FileInputStream
                    if (fis != null) {
                        synchronized(hijackedStreams) {
                            hijackedStreams.remove(fis)
                            streamCursor.remove(fis)
                            streamMark.remove(fis)
                        }
                    }
                    chain.proceed()
                }
            handles.add(h)
        }

        module.log(
            Log.INFO, TAG,
            "Hooked FileInputStream for $ELSA_CONFIG_PATH (ctors=${ctors.size}, read0=${read0 != null}, read3=${read3 != null})."
        )
    }

    /**
     * 读取原始 XML、构建增强字节并登记到 [hijackedStreams]。
     * 在构造 Hook 内同步执行；失败时放弃劫持（不影响系统正常读取原文件）。
     */
    private fun hijackStream(
        module: XposedModule,
        config: SwipeGuardConfig,
        fis: FileInputStream,
    ) {
        // 读取原始内容；此处 read 调用会被本模块 read Hook 捕获，
        // 但 hijackedStreams 尚未登记该 fis，故会透传 proceed()。
        val originalBytes = fis.readBytes()
        val originalXml = String(originalBytes, Charsets.UTF_8)

        // 模块禁用或白名单为空时无需改写，避免无谓 XML 拼接
        if (!config.enabled || config.protectedApps.isEmpty()) {
            module.log(Log.DEBUG, TAG, "Config empty/disabled, skip elsa hijack.")
            return
        }

        val enhancedXml = XmlPolicyBuilder.buildEnhancedXml(originalXml, config)
        val enhancedBytes = enhancedXml.toByteArray(Charsets.UTF_8)

        synchronized(hijackedStreams) {
            hijackedStreams[fis] = enhancedBytes
            streamCursor[fis] = 0
        }
        module.log(
            Log.INFO, TAG,
            "Elsa config hijacked: original=${originalBytes.size}B enhanced=${enhancedBytes.size}B protected=${config.protectedApps.size}"
        )
    }

    /**
     * 从劫持缓冲向 [buf] 的 `[off, off+len)` 区间写入数据。
     *
     * 读 pos + arraycopy + 写 pos 合并在同一个 synchronized 块中，
     * 避免 TOCTOU 竞态（两个线程同时读到相同 pos）。
     *
     * @return 实际写入字节数；`-1` 表示已到增强缓冲末尾（EOF）；
     *         `null` 表示该流未被劫持，调用方应透传 `proceed()`。
     */
    private fun serveHijacked(fis: FileInputStream, buf: ByteArray, off: Int, len: Int): Int? {
        synchronized(hijackedStreams) {
            val data = hijackedStreams[fis] ?: return null
            val pos = streamCursor[fis] ?: 0
            if (pos >= data.size) return -1 // 增强缓冲已读完 → EOF
            val n = minOf(len, data.size - pos)
            System.arraycopy(data, pos, buf, off, n)
            streamCursor[fis] = pos + n
            return n
        }
    }

    /**
     * 返回劫持缓冲的可用字节数。
     *
     * @return 可用字节数，`null` 表示该流未被劫持。
     */
    private fun serveAvailable(fis: FileInputStream): Int? {
        synchronized(hijackedStreams) {
            val data = hijackedStreams[fis] ?: return null
            val pos = streamCursor[fis] ?: 0
            return (data.size - pos).coerceAtLeast(0)
        }
    }

    /**
     * 在劫持缓冲上模拟 skip 操作。
     *
     * @return 实际跳过的字节数，`null` 表示该流未被劫持。
     */
    private fun serveSkip(fis: FileInputStream, n: Long): Long? {
        synchronized(hijackedStreams) {
            val data = hijackedStreams[fis] ?: return null
            val pos = streamCursor[fis] ?: 0
            val actualSkip = minOf(n.coerceAtLeast(0), (data.size - pos).toLong())
            streamCursor[fis] = (pos + actualSkip.toInt())
            return actualSkip
        }
    }

    /**
     * 在劫持缓冲上模拟 mark 操作：保存当前游标位置。
     *
     * @return true 表示已处理（流被劫持），false 表示透传 proceed()。
     */
    private fun serveMark(fis: FileInputStream): Boolean {
        synchronized(hijackedStreams) {
            if (!hijackedStreams.containsKey(fis)) return false
            val pos = streamCursor[fis] ?: 0
            streamMark[fis] = pos
            return true
        }
    }

    /**
     * 在劫持缓冲上模拟 reset 操作：恢复游标到上次 mark 的位置。
     *
     * @return true 表示已处理（流被劫持），false 表示透传 proceed()。
     */
    private fun serveReset(fis: FileInputStream): Boolean {
        synchronized(hijackedStreams) {
            if (!hijackedStreams.containsKey(fis)) return false
            val mark = streamMark[fis]
            if (mark == null) return false // 未 mark 过则无法 reset
            streamCursor[fis] = mark
            return true
        }
    }

    /**
     * @return true 如果该流被劫持（支持 mark/reset），`null` 透传。
     */
    private fun serveMarkSupported(fis: FileInputStream): Boolean? {
        synchronized(hijackedStreams) {
            return if (hijackedStreams.containsKey(fis)) true else null
        }
    }
}

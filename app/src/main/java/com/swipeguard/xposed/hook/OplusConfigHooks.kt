package com.swipeguard.xposed.hook

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.swipeguard.xposed.data.IConfigRepository
import com.swipeguard.xposed.data.JsonCodec
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
 *     读取 `startup/autostart_white_list.txt`。拦截后追加/移除模块白名单包名，
 *     使白名单内应用免于启动期冻结。
 *
 *  2. **冻结策略 XML**：系统通过 `FileInputStream` 读取
 *     `/data/oplus/os/bpm/sys_elsa_config_list.xml`。拦截后将原始 XML 经
 *     [XmlPolicyBuilder.buildEnhancedXml] 注入/移除 whitePkg 后返回，
 *     从而在不修改磁盘文件的前提下动态改写冻结策略。
 *
 * 容错：每个 Hook 点独立 try-catch；类/方法查找失败（非 ColorOS 或版本差异）
 * 仅记录日志后跳过，绝不抛出导致 system_server 崩溃。
 *
 * 线程安全：[hijackedStreams] 用 [java.util.WeakHashMap] + 自身同步锁保护，
 * 与 [XmlPolicyBuilder]（无状态）配合，可被 system_server 多线程并发调用。
 * [currentConfig] / [currentEffectiveSet] 由调用方 ([ModuleMain]) 保证可见性。
 *
 * @param module    [ModuleMain] 实例，提供 `hook()` / `log()` 等 [XposedInterface] 能力
 * @param config    SwipeGuard 配置快照（线程安全由调用方保证，Hook 闭包按调用时机读取）
 * @param classLoader system_server ClassLoader，用于查找 OplusSettings
 */
object OplusConfigHooks {

    private const val TAG = "SwipeGuard/OplusCfg"

    /**
     * SharedPreferences 文件名，必须与 UI 进程和 [RemoteConfigRepository] 一致。
     * 用于持久化从设备 XML 提取的系统默认白名单。
     */
    private const val PREFS_NAME = "swipeguard_config"

    /** hijackStream 重入守卫：防止 ctor Hook→readBytes→read Hook→递归触发。 */
    @Volatile
    private var inHijack = false

    /** 当前配置快照；通过 [updateConfig] 热更新，供所有 Hook 拦截闭包读取。 */
    @Volatile
    private var currentConfig: SwipeGuardConfig = SwipeGuardConfig.DEFAULT

    /**
     * 当前有效白名单 = (systemDefaults - userRemovals) + userAdditions。
     * 由 [updateConfig] 计算，供拦截闭包快速判断。
     */
    @Volatile
    private var currentEffectiveSet: Set<String> = emptySet()

    /** 共享的 RemotePreferences 引用（用于回写提取的系统默认白名单）。 */
    @Volatile
    private var remotePrefs: SharedPreferences? = null

    /** 自启动白名单文件名（相对路径，readConfig 第二参数）。 */
    private const val AUTOSTART_WHITELIST_FILE = "startup/autostart_white_list.txt"

    /** 冻结策略 XML 的绝对路径。 */
    private const val ELSA_CONFIG_PATH = "/data/oplus/os/bpm/sys_elsa_config_list.xml"

    /**
     * 单个 FileInputStream 的劫持状态。
     *
     * 将原来的 [data, cursor, mark] 三个独立 WeakHashMap 合并为一个
     * data class，减少三次 synchronized 查询的开销。
     *
     * @property data  增强后的字节缓冲（原始 XML + 注入 whitePkg）
     * @property cursor 当前读取游标位置
     * @property mark  mark() 保存的游标（-1 表示未 mark）
     */
    private data class StreamState(
        var data: ByteArray,
        var cursor: Int = 0,
        var mark: Int = -1,
    )

    /**
     * 被「劫持」的 FileInputStream → 状态对象。
     *
     * 使用 [java.util.WeakHashMap] 避免泄漏已关闭的流对象。
     * 同步访问通过 [streamStates] 自身作为锁。
     */
    private val streamStates: java.util.WeakHashMap<FileInputStream, StreamState> =
        java.util.WeakHashMap()

    /**
     * 安装全部 ColorOS 策略读取 Hook。可被多次调用（重复 install 会注册多个
     * Hook，调用方需自行控制；当前仅 [ModuleMain.onSystemServerStarting] 调用一次）。
     *
     * @param config   当前 SwipeGuard 配置快照；Hook 闭包捕获此引用，由 [ModuleMain] 热更新
     * @param handles  安装成功的 Hook 句柄将追加到此列表，便于统一卸载 / hot-reload
     */
    fun install(
        module: XposedModule,
        config: SwipeGuardConfig,
        classLoader: ClassLoader,
        handles: MutableList<XposedInterface.HookHandle>,
    ) {
        currentConfig = config
        currentEffectiveSet = (config.systemDefaults - config.userRemovals) + config.userAdditions

        // 保存 RemotePreferences 引用，供 hijackStream 回写系统默认白名单
        remotePrefs = try {
            module.getRemotePreferences(PREFS_NAME)
        } catch (_: Throwable) {
            null
        }

        installOplusSettingsReadConfig(module, config, classLoader, handles)
        installElsaConfigFileInputStream(module, config, handles)
    }

    /**
     * 热更新配置快照。由 [ModuleMain.syncHooks] 在配置热加载后调用，
     * 替换 [currentConfig] 并重新计算有效白名单（systemDefaults 已在 config 中）。
     */
    fun updateConfig(config: SwipeGuardConfig) {
        currentConfig = config
        currentEffectiveSet = (config.systemDefaults - config.userRemovals) + config.userAdditions

        // 刷新已劫持的 XML 缓冲，使热添加的包名即时生效
        synchronized(streamStates) {
            val iterator = streamStates.entries.iterator()
            while (iterator.hasNext()) {
                val (fis, state) = iterator.next()
                try {
                    val originalXml = String(state.data, Charsets.UTF_8)
                    val enhancedXml = XmlPolicyBuilder.buildEnhancedXml(originalXml, config)
                    val enhancedBytes = enhancedXml.toByteArray(Charsets.UTF_8)
                    // 重置游标，允许重新读取
                    state.data = enhancedBytes
                    state.cursor = 0
                    state.mark = -1
                } catch (t: Throwable) {
                    iterator.remove() // 刷新失败则移除劫持，回退到原始内容
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Hook 1: OplusSettings.readConfig
    // ------------------------------------------------------------------

    /**
     * Hook `OplusSettings.readConfig(Context, String, int)`。
     *
     * 当 `fileName == "startup/autostart_white_list.txt"` 时，先放行原始调用拿到
     * 系统白名单内容（通常为 String），再用 [XmlPolicyBuilder.buildEnhancedAutoStartWhiteList]
     * 追加/移除模块白名单包名后替换返回值。
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
                        val cfg = currentConfig
                        if (!cfg.enabled || currentEffectiveSet.isEmpty()) {
                            return@intercept result
                        }
                        val enhanced = XmlPolicyBuilder.buildEnhancedAutoStartWhiteList(
                            originalContent = result,
                            config = cfg
                        )
                        module.log(
                            Log.DEBUG, TAG,
                            "readConfig($fileName) adjusted: " +
                            "additions=${cfg.userAdditions.size} removals=${cfg.userRemovals.size}"
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
                        hijackStream(module, fis)
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
                        synchronized(streamStates) {
                            streamStates.remove(fis)
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
     * 读取原始 XML、提取系统默认白名单、构建增强字节并登记到 [hijackedStreams]。
     * 在构造 Hook 内同步执行；失败时放弃劫持（不影响系统正常读取原文件）。
     */
    private fun hijackStream(
        module: XposedModule,
        fis: FileInputStream,
    ) {
        if (inHijack) return  // 重入守卫，防止 read Hook 递归触发
        inHijack = true
        try {
        // 读取原始内容；此处 read 调用会被本模块 read Hook 捕获，
        // 但 hijackedStreams 尚未登记该 fis，故会透传 proceed()。
        val originalBytes = fis.readBytes()
        val originalXml = String(originalBytes, Charsets.UTF_8)

        // 从 XML 中提取系统默认白名单，与用户配置合并计算有效白名单
        val extractedDefaults = XmlPolicyBuilder.extractWhitePkgNames(originalXml)
        val cfg = currentConfig
        val effectiveSet = (extractedDefaults - cfg.userRemovals) + cfg.userAdditions

        // ★ 将设备真实系统默认白名单写回 SharedPreferences，
        //   确保 SwipeKillHooks/AthenaKillHooks/WhitePkgLookupHooks 使用正确的 systemDefaults
        persistExtractedDefaults(extractedDefaults)

        // 模块禁用或有效白名单为空时无需改写
        if (!cfg.enabled || effectiveSet.isEmpty()) {
            module.log(Log.DEBUG, TAG, "Config empty/disabled, skip elsa hijack.")
            return
        }

        val enhancedXml = XmlPolicyBuilder.buildEnhancedXml(originalXml, cfg)
        val enhancedBytes = enhancedXml.toByteArray(Charsets.UTF_8)

        synchronized(streamStates) {
            streamStates[fis] = StreamState(data = enhancedBytes)
        }
        module.log(
            Log.INFO, TAG,
            "Elsa config hijacked: original=${originalBytes.size}B enhanced=${enhancedBytes.size}B " +
            "systemDefaults=${extractedDefaults.size} effective=${effectiveSet.size}"
        )
    } finally {
        inHijack = false
    }
    }

    // ------------------------------------------------------------------
    // 系统默认白名单持久化
    // ------------------------------------------------------------------

    /**
     * 将从设备 XML 中提取的系统默认白名单写回 SharedPreferences，
     * 使 [SwipeKillHooks]、[AthenaKillHooks] 和 [WhitePkgLookupHooks]
     * 使用正确的系统默认值，而非硬编码的 [SwipeGuardConfig.Companion.KNOWN_SYSTEM_DEFAULTS]。
     *
     * 仅在以下条件满足时写入：
     * 1. [remotePrefs] 可用（已在 [install] 时获取）
     * 2. 提取的默认集非空
     * 3. 提取的默认集与当前配置的 systemDefaults 不同（避免每次启动都刷盘）
     *
     * 写入后通过 Binder 同步到 UI 进程的本地 SharedPreferences，
     * 持久化存储，下次启动时 [SwipeGuardConfig.fromJson] 直接加载设备真实值。
     *
     * 使用 [commit] 而非 [apply] 以确保数据在后续 Hook 初始化前落盘。
     */
    private fun persistExtractedDefaults(extractedDefaults: Set<String>) {
        if (extractedDefaults.isEmpty()) return
        val prefs = remotePrefs ?: return
        try {
            // 读取当前配置，检查是否已有更完整的 systemDefaults
            val currentJson = prefs.getString(IConfigRepository.KEY_CONFIG_JSON, null)
            if (currentJson.isNullOrEmpty()) return
            val currentCfg = JsonCodec.decode(currentJson)

            // 仅在提取的默认集比当前 systemDefaults 更大或更新时才写回
            // 避免覆盖用户手动清空的 systemDefaults
            if (!currentCfg.systemDefaultsInitialized) {
                // systemDefaults 尚未初始化（首次加载），跳过
                return
            }

            // 如果提取的默认集 > 当前硬编码默认集，说明设备有更多系统条目
            val currentDefaults = currentCfg.systemDefaults
            if (extractedDefaults.size <= currentDefaults.size &&
                currentDefaults.containsAll(extractedDefaults)
            ) {
                return  // 当前默认集已足够完整，无需更新
            }

            // 合并：保留 currentDefaults 中已有的条目，补充提取的新条目
            val merged = currentDefaults + extractedDefaults
            val updatedCfg = currentCfg.copy(systemDefaults = merged)
            val updatedJson = JsonCodec.encode(updatedCfg)

            prefs.edit().putString(IConfigRepository.KEY_CONFIG_JSON, updatedJson).commit()

            Log.i(
                TAG,
                "System defaults updated: ${currentDefaults.size} → ${merged.size} " +
                "(added ${merged.size - currentDefaults.size} from device XML)"
            )

            // 同步到本地快照，确保当前进程中的后续 Hook 立即生效
            // currentConfig 在此处更新，但 currentConfig.systemDefaults 是 data class 字段
            // 需要整体替换
            val newCfg = currentConfig.copy(systemDefaults = merged)
            updateConfig(newCfg)
        } catch (t: Throwable) {
            // 持久化失败不阻塞 XML 注入逻辑；仅 WARN 日志
            Log.w(TAG, "Failed to persist extracted system defaults", t)
        }
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
        synchronized(streamStates) {
            val state = streamStates[fis] ?: return null
            val data = state.data
            val pos = state.cursor
            if (pos >= data.size) return -1 // 增强缓冲已读完 → EOF
            val n = minOf(len, data.size - pos)
            System.arraycopy(data, pos, buf, off, n)
            state.cursor = pos + n
            return n
        }
    }

    /**
     * 返回劫持缓冲的可用字节数。
     *
     * @return 可用字节数，`null` 表示该流未被劫持。
     */
    private fun serveAvailable(fis: FileInputStream): Int? {
        synchronized(streamStates) {
            val state = streamStates[fis] ?: return null
            val pos = state.cursor
            return (state.data.size - pos).coerceAtLeast(0)
        }
    }

    /**
     * 在劫持缓冲上模拟 skip 操作。
     *
     * @return 实际跳过的字节数，`null` 表示该流未被劫持。
     */
    private fun serveSkip(fis: FileInputStream, n: Long): Long? {
        synchronized(streamStates) {
            val state = streamStates[fis] ?: return null
            val pos = state.cursor
            val actualSkip = minOf(n.coerceAtLeast(0), (state.data.size - pos).toLong())
            state.cursor = (pos + actualSkip.toInt())
            return actualSkip
        }
    }

    /**
     * 在劫持缓冲上模拟 mark 操作：保存当前游标位置。
     *
     * @return true 表示已处理（流被劫持），false 表示透传 proceed()。
     */
    private fun serveMark(fis: FileInputStream): Boolean {
        synchronized(streamStates) {
            val state = streamStates[fis] ?: return false
            state.mark = state.cursor
            return true
        }
    }

    /**
     * 在劫持缓冲上模拟 reset 操作：恢复游标到上次 mark 的位置。
     *
     * @return true 表示已处理（流被劫持），false 表示透传 proceed()。
     */
    private fun serveReset(fis: FileInputStream): Boolean {
        synchronized(streamStates) {
            val state = streamStates[fis] ?: return false
            if (state.mark < 0) return true // 未 mark 过则保持 cursor 不变，对齐 BufferedInputStream 语义
            state.cursor = state.mark
            return true
        }
    }

    /**
     * @return true 如果该流被劫持（支持 mark/reset），`null` 透传。
     */
    private fun serveMarkSupported(fis: FileInputStream): Boolean? {
        synchronized(streamStates) {
            return if (streamStates.containsKey(fis)) true else null
        }
    }
}

package com.swipeguard.xposed.hook

import android.content.SharedPreferences
import android.util.Log
import com.swipeguard.xposed.data.IConfigRepository
import com.swipeguard.xposed.data.RemoteConfigRepository
import com.swipeguard.xposed.model.SwipeGuardConfig
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * SwipeGuard Xposed 模块主入口（libxposed 现代 API）。
 *
 * 该类由 `META-INF/xposed/java_init.list` 声明，libxposed 框架在目标进程内
 * 自动实例化，**无需**在 AndroidManifest 中声明 `xposedmodule` meta-data。
 *
 * 核心职责（重写后去除 PolicyMatcher 依赖，直接持有 [SwipeGuardConfig]）：
 *  1. 在 system_server 启动阶段（[onSystemServerStarting]）通过
 *     `XposedInterface.getRemotePreferences` 获取远端 SharedPreferences，
 *     构造 [RemoteConfigRepository] 并加载配置快照。
 *  2. 订阅配置热更新：UI 进程改写 SharedPreferences 后，重新加载配置并调用
 *     [syncHooks] 让各 Hook 同步最新快照。
 *  3. 安装 3 个相互独立的 Hook：
 *     - [OplusConfigHooks]：ColorOS OFreezer 策略注入（autostart 白名单 + elsa XML）。
 *     - [SwipeKillHooks]：拦截 `ActivityManagerService.killBackgroundProcesses`，
 *       对白名单包名跳过 kill。
 *     - [AthenaKillHooks]：拦截系统自有 API 杀进程（athenaKill/clearProcess），
 *       作为 SwipeKillHooks 的补充。
 *
 * 容错原则：所有初始化与 Hook 安装均 try-catch 包裹，**任何失败都不允许
 * 导致 system_server 崩溃**——失败时仅记录日志并降级为「无管控」模式。
 *
 * 关于 `XposedService`：libxposed-service 的 `XposedService` 通过
 * `XposedServiceHelper` 异步绑定，且仅在模块自身进程中可用；system_server
 * 等 Hook 进程必须使用 `XposedInterface.getRemotePreferences` 直接获取远端
 * SharedPreferences，故 [RemoteConfigRepository] 在此处采用仅接收
 * [android.content.SharedPreferences] 的构造。
 */
class ModuleMain : XposedModule() {

    /** 当前配置快照；热更新时整体替换以保持对 Hook 闭包的可见性。 */
    private lateinit var config: SwipeGuardConfig

    /** 配置仓储引用，持有以维持监听器生命周期。 */
    private lateinit var configRepo: RemoteConfigRepository

    /** AOSP 标准杀进程拦截 Hook。 */
    private lateinit var swipeKillHooks: SwipeKillHooks

    /** Athena 自有 API 杀进程拦截 Hook。 */
    private lateinit var athenaKillHooks: AthenaKillHooks

    /** OFreezer 运行时白名单查询拦截 Hook（决策层保护）。 */
    private var whitePkgLookupHooks: WhitePkgLookupHooks? = null

    /** Athena Binder 入口拦截 Hook（在 com.oplus.athena 进程中运行）。 */
    private var athenaBinderHooks: AthenaBinderHooks? = null

    /** Athena 进程配置热更新监听器引用（防止 GC）。 */
    private var athenaConfigListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    // SystemServiceHooks removed: 冻结已由第三方墓碑模块接管，参见 .pi/context/plan.md t7

    /**
     * system_server 启动回调 —— 模块在 system_server 进程中只触发一次。
     *
     * 该回调早于 PackageManagerService / ActivityManagerService 等关键服务
     * 的初始化，是安装 OFreezer / AMS Hook 的最佳时机。
     */
    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        try {
            // 0. 能力检查：框架必须支持 Hook system_server（PROP_CAP_SYSTEM）。
            //    不支持时直接放弃，避免在嵌入式 / 受限框架上误注册。
            if ((getFrameworkProperties() and XposedInterface.PROP_CAP_SYSTEM) == 0L) {
                log(Log.WARN, TAG, "Framework does not support system server hooks")
                return
            }

            log(
                Log.INFO, TAG,
                "onSystemServerStarting: framework=${getFrameworkName()}/${getFrameworkVersion()}"
            )

            // 1. 通过 XposedInterface（本模块自身）获取远端 SharedPreferences，
            //    构造 RemoteConfigRepository。prefsName 必须与 UI 进程的
            //    LocalConfigRepository.PREFS_NAME 保持一致（"swipeguard_config"）。
            val prefs = getRemotePreferences(PREFS_NAME)
            configRepo = RemoteConfigRepository(prefs)
            config = configRepo.load()

            // 2. 订阅配置热更新：UI 进程改写 SharedPreferences 后，框架在 Binder
            //    线程回调。监听 config JSON（systemDefaults 已内嵌其中）的变更。
            prefs.registerOnSharedPreferenceChangeListener { _, key ->
                if (key == IConfigRepository.KEY_CONFIG_JSON || key == null) {
                    try {
                        config = configRepo.load()
                        syncHooks()
                        log(Log.INFO, TAG, "Config hot-reloaded (key=$key)")
                    } catch (t: Throwable) {
                        // reload 失败不应影响已安装的 Hook；旧快照继续生效。
                        log(Log.ERROR, TAG, "Config reload failed", t)
                    }
                }
            }

            val classLoader = param.classLoader

            // 3. 安装 3 个独立 Hook，顺序固定：
            //    OplusConfig 先注入白名单 → SwipeKill 再拦截 kill → 形成配置→拦截闭环
            //    各 Hook 模块内部对类/方法查找失败均有容错，
            //    不会因 ColorOS 版本差异抛出。
            var installed = 0
            var failed = 0

            if (tryInstall("OplusConfigHooks") {
                OplusConfigHooks.install(
                    module = this,
                    config = config,
                    classLoader = classLoader,
                    handles = mutableListOf(),
                    prefs = prefs,  // 传递 RemotePreferences 引用用于持久化系统默认白名单
                )
            }) installed++ else failed++

            if (tryInstall("SwipeKillHooks") {
                swipeKillHooks = SwipeKillHooks(this, classLoader)
                swipeKillHooks.syncConfig(configRepo)
                swipeKillHooks.install()
            }) installed++ else failed++

            // Athena 自有 API 拦截（如找到 athenaKill 则优先于此路径保护）
            if (tryInstall("AthenaKillHooks") {
                athenaKillHooks = AthenaKillHooks(this, classLoader)
                athenaKillHooks.syncConfig(configRepo)
                athenaKillHooks.install()
            }) installed++ else failed++

            // ★ 新增：运行时 whitePkg 查询拦截（OFreezer 决策层保护）
            // 使 OFreezer 在 kill 决策阶段就认为白名单应用在白名单中，
            // 从而完全跳过杀进程路径。与 SwipeKillHooks 形成决策+执行双层保护。
            if (tryInstall("WhitePkgLookupHooks") {
                whitePkgLookupHooks = WhitePkgLookupHooks(this, classLoader)
                whitePkgLookupHooks.syncConfig(configRepo)
                whitePkgLookupHooks.install()
            }) installed++ else failed++

            // SystemServiceHooks removed: 冻结已由第三方墓碑模块接管，参见 .pi/context/plan.md t7

            if (failed == 0) {
                log(
                    Log.INFO, TAG,
                    "All $installed hooks installed successfully. " +
                    "Additions: ${config.userAdditions.size} Removals: ${config.userRemovals.size}"
                )
            } else {
                log(
                    Log.WARN, TAG,
                    "$installed/${installed + failed} hooks installed, $failed failed. " +
                    "Additions: ${config.userAdditions.size} Removals: ${config.userRemovals.size}"
                )
            }
        } catch (t: Throwable) {
            // 顶层兜底：任何未预期异常都仅记录，绝不让 system_server 崩溃。
            log(Log.ERROR, TAG, "Module init failed", t)
        }
    }

    /**
     * 配置热更新时同步各 Hook 的内部快照。
     *
     * 从 [configRepo] 加载最新配置（systemDefaults 已内嵌其中），计算有效白名单后
     * 同步给 [OplusConfigHooks] / [SwipeKillHooks] / [AthenaKillHooks]。
     */
    private fun syncHooks() {
        // OplusConfigHooks 通过 updateConfig 热更新白名单，无需重新 install
        OplusConfigHooks.updateConfig(config)
        if (::swipeKillHooks.isInitialized) swipeKillHooks.syncConfig(configRepo)
        if (::athenaKillHooks.isInitialized) athenaKillHooks.syncConfig(configRepo)
        whitePkgLookupHooks?.syncConfig(configRepo)
        athenaBinderHooks?.syncConfig(configRepo)
        // SystemServiceHooks removed: 冻结已由第三方墓碑模块接管，参见 .pi/context/plan.md t7
    }

    /**
     * 模块被加载回调。在 system_server 中先于 [onSystemServerStarting] 的等价
     * 包加载回调；本模块不在该阶段做 Hook，仅记录诊断信息。
     */
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(
            Log.DEBUG, TAG,
            "onModuleLoaded: process=${param.processName} isSystemServer=${param.isSystemServer}"
        )
    }

    /** 应用包加载回调。记录包加载事件；Hook 安装移至 [onPackageReady]。 */
    override fun onPackageLoaded(param: PackageLoadedParam) {
        // 仅记录日志；实际安装延后到 onPackageReady（ClassLoader 就绪后）
        if (param.packageName == "com.oplus.athena") {
            log(Log.DEBUG, TAG, "onPackageLoaded: com.oplus.athena — deferring to onPackageReady")
        }
    }

    /**
     * 应用 ClassLoader 就绪回调。
     * 当目标包为 com.oplus.athena 时，安装 Binder 入口拦截 Hook。
     * [PackageReadyParam.classLoader] 在此阶段可用。
     */
    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName == "com.oplus.athena") {
            try {
                // 在 Athena 进程中获取配置（若尚未初始化）
                if (!::configRepo.isInitialized) {
                    val prefs = getRemotePreferences(PREFS_NAME)
                    configRepo = RemoteConfigRepository(prefs)
                }
                athenaBinderHooks = AthenaBinderHooks(this, param.classLoader)
                athenaBinderHooks?.syncConfig(configRepo)
                athenaBinderHooks?.install()

                // 注册配置热更新监听（Athena 进程独立 listener，跨进程需单独注册）
                val prefs = getRemotePreferences(PREFS_NAME)
                athenaConfigListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == IConfigRepository.KEY_CONFIG_JSON || key == null) {
                        try {
                            config = configRepo.load()
                            athenaBinderHooks?.syncConfig(configRepo)
                            log(Log.INFO, TAG, "AthenaBinderHooks config hot-reloaded")
                        } catch (t: Throwable) {
                            log(Log.ERROR, TAG, "AthenaBinderHooks reload failed", t)
                        }
                    }
                }
                prefs.registerOnSharedPreferenceChangeListener(athenaConfigListener)

                log(Log.INFO, TAG, "AthenaBinderHooks installed for com.oplus.athena")
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "AthenaBinderHooks install failed", t)
            }
        }
    }

    /**
     * 统一 try-catch 安装辅助，捕获任何 [Throwable] 并以统一格式记录错误日志。
     * 内联函数避免 lambda 额外分配开销。
     *
     * @return true 安装成功，false 安装失败。
     */
    private inline fun tryInstall(name: String, block: () -> Unit): Boolean {
        return try {
            block()
            true
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "$name install failed", t)
            false
        }
    }

    companion object {
        private const val TAG = "SwipeGuard"

        /**
         * RemotePreferences 分组名，必须与 UI 进程的
         * `LocalConfigRepository.PREFS_NAME` 完全一致，否则跨进程同步失效。
         */
        internal const val PREFS_NAME: String = "swipeguard_config"
    }
}

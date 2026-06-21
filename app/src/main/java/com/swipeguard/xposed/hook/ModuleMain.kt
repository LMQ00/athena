package com.swipeguard.xposed.hook

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
 *     - [SystemServiceHooks]：AOSP OomAdjuster 辅助保活，对白名单进程强制低
 *       oom_score_adj，缓解 LMK 杀进程。
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

    /** 划卡杀进程拦截 Hook（实例化以持有可变的 config 快照）。 */
    private lateinit var swipeKillHooks: SwipeKillHooks

    /** 系统服务辅助保活 Hook（实例化以持有可变的 config 快照）。 */
    private lateinit var systemServiceHooks: SystemServiceHooks

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
            //    线程回调。仅当配置 JSON key 变更（或 key == null 表示全量回调）
            //    时重新加载并同步给各 Hook。
            prefs.registerOnSharedPreferenceChangeListener { _, key ->
                if (key == IConfigRepository.KEY_CONFIG_JSON || key == null) {
                    try {
                        config = configRepo.load()
                        syncHooks()
                        log(Log.INFO, TAG, "Config hot-reloaded")
                    } catch (t: Throwable) {
                        // reload 失败不应影响已安装的 Hook；旧快照继续生效。
                        log(Log.ERROR, TAG, "Config reload failed", t)
                    }
                }
            }

            val classLoader = param.classLoader

            // 3. 安装 3 个独立 Hook。各 Hook 模块内部对类/方法查找失败均有容错,
            //    不会因 ColorOS 版本差异抛出。
            //    注意：OplusConfigHooks 为 object，install 捕获当前 config 快照,
            //    热更新不重新 install（与任务规格一致）；SwipeKillHooks /
            //    SystemServiceHooks 为实例，通过 syncConfig(repo) 刷新快照。
            try {
                OplusConfigHooks.install(this, config, classLoader, mutableListOf())
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "OplusConfigHooks.install failed.", t)
            }

            try {
                swipeKillHooks = SwipeKillHooks(this)
                swipeKillHooks.syncConfig(configRepo)
                swipeKillHooks.install()
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "SwipeKillHooks install failed.", t)
            }

            try {
                systemServiceHooks = SystemServiceHooks(this)
                systemServiceHooks.syncConfig(configRepo)
                systemServiceHooks.install()
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "SystemServiceHooks install failed.", t)
            }

            log(
                Log.INFO, TAG,
                "All hooks installed. Protected apps: ${config.protectedApps.size}"
            )
        } catch (t: Throwable) {
            // 顶层兜底：任何未预期异常都仅记录，绝不让 system_server 崩溃。
            log(Log.ERROR, TAG, "Module init failed", t)
        }
    }

    /**
     * 配置热更新时同步各实例化 Hook 的内部快照。
     *
     * [OplusConfigHooks] 为 object 且 install 时捕获 config 快照，不在此处
     * 刷新（与任务规格一致）；仅 [SwipeKillHooks] / [SystemServiceHooks]
     * 通过 syncConfig(repo) 重新加载配置。
     */
    private fun syncHooks() {
        if (::swipeKillHooks.isInitialized) swipeKillHooks.syncConfig(configRepo)
        if (::systemServiceHooks.isInitialized) systemServiceHooks.syncConfig(configRepo)
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

    /** 应用包加载回调（system_server 中不触发，普通应用进程可选扩展点）。 */
    override fun onPackageLoaded(param: PackageLoadedParam) {
        // 当前实现聚焦 system_server 侧冻结策略；应用进程内暂无干预需求。
    }

    /** 应用 ClassLoader 就绪回调（system_server 中不触发）。 */
    override fun onPackageReady(param: PackageReadyParam) {
        // 预留扩展点。
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

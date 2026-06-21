package com.athena.xposed.hook

import android.util.Log
import com.athena.xposed.data.IConfigRepository
import com.athena.xposed.data.RemoteConfigRepository
import com.athena.xposed.engine.IPolicyMatcher
import com.athena.xposed.engine.PolicyMatcher
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Athena Xposed 模块主入口（libxposed 现代 API）。
 *
 * 该类由 `META-INF/xposed/java_init.list` 声明，libxposed 框架在目标进程内
 * 自动实例化，**无需**在 AndroidManifest 中声明 `xposedmodule` meta-data。
 *
 * 核心职责：
 *  1. 在 system_server 启动阶段（[onSystemServerStarting]）建立配置链路：
 *     `XposedInterface.getRemotePreferences` → [RemoteConfigRepository] →
 *     [PolicyMatcher]，并订阅配置热更新。
 *  2. 调用 [installHooks] 将 [OplusConfigHooks]（ColorOS OFreezer 策略注入）
 *     与 [SystemServiceHooks]（LMK 辅助保活）挂载到 system_server。
 *
 * 容错原则：所有初始化与 Hook 安装均 try-catch 包裹，**任何失败都不允许
 * 导致 system_server 崩溃**——失败时仅记录日志并降级为「无管控」模式。
 *
 * 关于 `XposedService`：libxposed-service 的 `XposedService` 通过
 * `XposedServiceHelper` 异步绑定，且仅在模块自身进程中可用；system_server
 * 等 Hook 进程必须使用 `XposedInterface.getRemotePreferences`（本模块继承自
 * [XposedInterfaceWrapper] 即具备该方法）直接获取远端 SharedPreferences。
 */
class ModuleMain : XposedModule() {

    /** 已安装的 Hook 句柄，便于未来 hot-reload 时统一卸载。 */
    private val hookHandles: MutableList<XposedInterface.HookHandle> =
        java.util.Collections.synchronizedList(mutableListOf())

    /** 配置仓储引用，持有以维持监听器生命周期；模块卸载时 [close]。 */
    private var configRepo: IConfigRepository? = null

    /** 匹配引擎引用，供外部诊断（如 dump stats）使用。 */
    private var matcher: IPolicyMatcher? = null

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
                log(Log.WARN, TAG, "Framework lacks PROP_CAP_SYSTEM, skip system_server hooks.")
                return
            }

            log(
                Log.INFO, TAG,
                "onSystemServerStarting: framework=${getFrameworkName()}/${getFrameworkVersion()}"
            )

            // 1. 通过 XposedInterface（本模块自身）获取远端 SharedPreferences，
            //    构造 RemoteConfigRepository。prefsName 必须与 UI 进程的
            //    LocalConfigRepository.PREFS_NAME 保持一致（"athena_config"）。
            val prefs = getRemotePreferences(PREFS_NAME)
            val repo: IConfigRepository = RemoteConfigRepository(prefs)
            configRepo = repo

            // 2. 初始化 PolicyMatcher 并加载当前配置快照。
            //    配置加载失败时 RemoteConfigRepository 已回退到 DEFAULT，
            //    不会抛出，此处再兜一层 try-catch 以防万一。
            val initialConfig = try {
                repo.load()
            } catch (t: Throwable) {
                log(Log.ERROR, TAG, "Initial config load failed, using DEFAULT.", t)
                com.athena.xposed.model.AthenaConfig.DEFAULT
            }
            val engine: IPolicyMatcher = PolicyMatcher(initialConfig)
            matcher = engine

            // 3. 订阅配置热更新：UI 进程改写 SharedPreferences 后，框架在 Binder
            //    线程回调；此处仅做无锁 reload（PolicyMatcher 内部 AtomicReference
            //    整体替换），Hook 读路径自动看到新快照。
            repo.observeChanges { newConfig ->
                try {
                    // 在 Binder 线程回调中加载配置时，先做一次 JSON 健全性校验，
                    // 避免因 SP 通知重排序窗口读到「正在写」的中间值。
                    runCatching {
                        val serialized = com.athena.xposed.data.JsonCodec.encode(newConfig)
                        com.athena.xposed.data.JsonCodec.decode(serialized)
                    }.getOrElse { err ->
                        log(Log.WARN, TAG, "Config sanity check failed, skip reload.", err)
                        return@observeChanges
                    }
                    engine.reload(newConfig)
                    log(Log.INFO, TAG, "Config hot-reloaded: ${engine.stats()}")
                } catch (t: Throwable) {
                    // reload 失败不应影响已安装的 Hook；旧快照继续生效。
                    log(Log.ERROR, TAG, "Config hot-reload failed, keep old snapshot.", t)
                }
            }

            // 4. 安装所有 Hook。各 Hook 模块内部对类/方法查找失败均有容错，
            //    不会因 ColorOS 版本差异抛出。
            installHooks(engine, param.classLoader)

            log(Log.INFO, TAG, "Athena hooks installed in system_server.")
        } catch (t: Throwable) {
            // 顶层兜底：任何未预期异常都仅记录，绝不让 system_server 崩溃。
            log(Log.ERROR, TAG, "onSystemServerStarting aborted.", t)
        }
    }

    /**
     * 安装全部 system_server 侧 Hook。
     *
     * - [OplusConfigHooks]：拦截 ColorOS OFreezer 3.0 的策略文件读取
     *   （autostart 白名单 + sys_elsa_config_list.xml），注入模块策略。
     * - [SystemServiceHooks]：AOSP OomAdjuster / ProcessList 辅助保活，
     *   对白名单进程强制低 oom_score_adj，缓解 LMK 杀进程。
     *
     * 两个模块独立安装，互不依赖；任一失败不影响另一个。
     *
     * @param engine      已初始化的策略匹配引擎（线程安全，可被 Hook 闭包长期持有）
     * @param classLoader system_server 的 ClassLoader，用于反射查找 OFreezer / AMS 内部类
     */
    private fun installHooks(engine: IPolicyMatcher, classLoader: ClassLoader) {
        try {
            OplusConfigHooks.install(this, engine, classLoader, hookHandles)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "OplusConfigHooks.install failed.", t)
        }
        try {
            SystemServiceHooks.install(this, engine, classLoader, hookHandles)
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "SystemServiceHooks.install failed.", t)
        }
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
        // 未来若需 per-app 进程内 Hook（如保活 Service onStartCommand 拦截）可在此扩展。
    }

    /** 应用 ClassLoader 就绪回调（system_server 中不触发）。 */
    override fun onPackageReady(param: PackageReadyParam) {
        // 预留扩展点，同上。
    }

    companion object {
        private const val TAG = "Athena"

        /**
         * RemotePreferences 分组名，必须与 UI 进程的
         * `LocalConfigRepository.PREFS_NAME` 完全一致，否则跨进程同步失效。
         */
        internal const val PREFS_NAME: String = "athena_config"
    }
}

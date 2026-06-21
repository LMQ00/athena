package com.athena.xposed.data

import android.content.SharedPreferences
import com.athena.xposed.model.AthenaConfig
import io.github.libxposed.service.XposedService

/**
 * 远端配置仓储 —— 用于 **Hook 进程**（system_server 等被 Hook 的系统进程）。
 *
 * 通过 Xposed 框架中转的 [SharedPreferences] 读取配置，该实例底层经 Binder 与模块
 * UI 进程的本地 SharedPreferences 同步。因此 Hook 进程无需文件系统权限即可读到
 * UI 进程写入的配置。
 *
 * 提供两个构造入口：
 *  - [RemoteConfigRepository(io.github.libxposed.service.XposedService, String)]：
 *    UI / 模块进程通过 `libxposed-service` 的 [XposedService] 获取远端 prefs。
 *  - [RemoteConfigRepository(android.content.SharedPreferences)]：Hook 进程
 *    （尤其是 system_server）直接传入由 `XposedInterface#getRemotePreferences`
 *    返回的 [SharedPreferences]。在 system_server 中 `libxposed-service` 的
 *    [XposedService] 不可用，必须使用本构造。
 *
 * 读写约束：
 * - **只读**：Hook 进程不应回写配置（避免双向写入竞态与权限问题），
 *   [save] 直接抛出 [UnsupportedOperationException]。
 * - 配置变更通过 [observeChanges] 订阅，底层为标准
 *   [SharedPreferences.OnSharedPreferenceChangeListener]，由框架在 Binder 线程回调。
 *
 * 异常策略：
 * - [load] 在 JSON 解析失败或 Binder 调用异常时回退到 [AthenaConfig.DEFAULT]，
 *   保证 Hook 进程在配置损坏时仍能以"放行/无管控"安全模式运行，绝不崩溃宿主。
 * - 经 [XposedService] 构造时，[XposedService.getRemotePreferences] 失败
 *   （如服务已死）会抛出 [XposedService.ServiceException]，由上层决定降级策略。
 */
class RemoteConfigRepository(
    private val prefs: SharedPreferences,
) : IConfigRepository {

    /**
     * UI / 模块进程构造：通过 `libxposed-service` 的 [XposedService] 获取远端
     * [SharedPreferences]。
     *
     * @param service   libxposed 服务入口。
     * @param prefsName 远端 preferences 分组名，必须与 UI 进程写入端一致
     *                  （[LocalConfigRepository] 使用 `"athena_config"`）。
     */
    constructor(service: XposedService, prefsName: String) : this(
        prefs = service.getRemotePreferences(prefsName)
    )

    /** 用户回调到 SharedPreferences 监听器的映射，便于精确注销。 */
    private val listeners: MutableMap<(AthenaConfig) -> Unit, SharedPreferences.OnSharedPreferenceChangeListener> =
        mutableMapOf()
    private val listenersLock = Any()

    @Synchronized
    override fun load(): AthenaConfig = try {
        val jsonStr = prefs.getString(KEY_CONFIG_JSON, null)
        if (jsonStr.isNullOrEmpty()) AthenaConfig.DEFAULT else JsonCodec.decode(jsonStr)
    } catch (t: Throwable) {
        // 解析失败 / Binder 异常：安全回退，保证宿主进程不崩溃。
        AthenaConfig.DEFAULT
    }

    override fun save(config: AthenaConfig) {
        throw UnsupportedOperationException(
            "RemoteConfigRepository is read-only in Hook process; config must be written by UI process."
        )
    }

    override fun observeChanges(listener: (AthenaConfig) -> Unit) {
        synchronized(listenersLock) {
            // 同一回调重复注册视为 no-op，避免底层注册多个 wrapper 导致重复触发。
            if (listeners.containsKey(listener)) return
            val spListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                // 在 Binder 线程触发；上层负责线程切换。读取最新配置后回调。
                listener.invoke(load())
            }
            listeners[listener] = spListener
            prefs.registerOnSharedPreferenceChangeListener(spListener)
        }
    }

    override fun removeOnChangeListener(listener: (AthenaConfig) -> Unit) {
        synchronized(listenersLock) {
            val spListener = listeners.remove(listener) ?: return
            prefs.unregisterOnSharedPreferenceChangeListener(spListener)
        }
    }

    override fun close() {
        synchronized(listenersLock) {
            listeners.values.forEach { prefs.unregisterOnSharedPreferenceChangeListener(it) }
            listeners.clear()
        }
    }
}

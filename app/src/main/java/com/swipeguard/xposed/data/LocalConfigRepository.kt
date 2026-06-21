package com.swipeguard.xposed.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.swipeguard.xposed.model.SwipeGuardConfig

/**
 * 本地配置仓储 —— 用于 **UI 进程**（SettingsActivity）。
 *
 * 直接使用标准 Android [SharedPreferences] 作为存储介质，写入的 JSON 字符串
 * 会被 Xposed 框架的 RemotePreferences 机制同步到 Hook 进程，由
 * [RemoteConfigRepository] 读取。
 *
 * 写入策略：[save] 使用 [SharedPreferences.Editor.apply] 异步落盘，避免在 UI
 * 线程执行磁盘 IO 造成卡顿。读出端（Hook 进程）通过 Binder 读到的是框架保证
 * 一致的快照，无需 UI 进程额外同步刷新。
 *
 * 线程安全：[load] 标注 [@Synchronized][Synchronized]，避免 UI 线程与监听器
 * 回调线程并发反序列化。监听器注册/注销使用独立的 [listenersLock]。
 *
 * @param context 任意有效 Context，内部未调用 [Context.getApplicationContext]，
 *        由调用方决定生命周期（推荐使用 application context 避免 Activity 泄漏）。
 */
class LocalConfigRepository(
    context: Context,
) : IConfigRepository {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val listeners: MutableMap<(SwipeGuardConfig) -> Unit, SharedPreferences.OnSharedPreferenceChangeListener> =
        mutableMapOf()
    private val listenersLock = Any()

    @Synchronized
    override fun load(): SwipeGuardConfig = try {
        val jsonStr = prefs.getString(KEY_CONFIG_JSON, null)
        if (jsonStr.isNullOrEmpty()) SwipeGuardConfig.DEFAULT else JsonCodec.decode(jsonStr)
    } catch (t: Throwable) {
        // 解析失败：安全回退默认配置，同时备份损坏的 JSON 以便排查。
        val jsonStr = prefs.getString(KEY_CONFIG_JSON, null)
        if (jsonStr != null) {
            runCatching {
                prefs.edit().putString(KEY_CONFIG_BAK, jsonStr).apply()
                Log.e(TAG, "Config parse failed, backed up to $KEY_CONFIG_BAK", t)
            }
        }
        SwipeGuardConfig.DEFAULT
    }

    override fun save(config: SwipeGuardConfig) {
        prefs.edit().putString(KEY_CONFIG_JSON, JsonCodec.encode(config)).apply()
    }

    override fun observeChanges(listener: (SwipeGuardConfig) -> Unit) {
        synchronized(listenersLock) {
            if (listeners.containsKey(listener)) return
            val spListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                // 直接解析 JSON，避免调用 @Synchronized 的 load() 导致自我等待。
                val cfg = try {
                    val jsonStr = prefs.getString(KEY_CONFIG_JSON, null)
                    if (jsonStr.isNullOrEmpty()) SwipeGuardConfig.DEFAULT else JsonCodec.decode(jsonStr)
                } catch (t: Throwable) {
                    SwipeGuardConfig.DEFAULT
                }
                listener.invoke(cfg)
            }
            listeners[listener] = spListener
            prefs.registerOnSharedPreferenceChangeListener(spListener)
        }
    }

    override fun removeOnChangeListener(listener: (SwipeGuardConfig) -> Unit) {
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

    private companion object {
        /** SharedPreferences 文件名，必须与 Hook 进程 [RemoteConfigRepository.prefsName] 一致。 */
        const val PREFS_NAME = "swipeguard_config"

        /** JSON 备份键名，用于解析失败时保存损坏数据。 */
        const val KEY_CONFIG_BAK = "swipeguard_config_json.bak"

        const val TAG = "SwipeGuard/LocalConfigRepo"
    }
}

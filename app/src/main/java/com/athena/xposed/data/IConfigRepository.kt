package com.athena.xposed.data

import com.athena.xposed.model.AthenaConfig

/**
 * SharedPreferences 中存储 [AthenaConfig] JSON 字符串的 key。
 *
 * UI 进程与 Hook 进程必须使用相同的 key，否则跨进程同步将失效。
 * 该常量为包级私有，仅由 [LocalConfigRepository] 与 [RemoteConfigRepository] 共享。
 */
internal const val KEY_CONFIG_JSON: String = "athena_config_json"

/**
 * 配置仓储接口。
 *
 * 抽象出 UI 进程（本地 SharedPreferences）与 Hook 进程（XposedService
 * RemotePreferences）之间统一的配置读写契约，使上层（PolicyMatcher 等）
 * 不感知配置来源差异。
 *
 * 同步约定：所有方法同步返回。本地 SharedPreferences 的读写本身就是同步的；
 * RemotePreferences 的 Binder 调用在 libxposed 实现中也是阻塞返回的，因此
 * 不需要额外的异步封装。
 *
 * 线程安全约定：
 * - [load] 标注 [@Synchronized][Synchronized]，避免并发读取时重复反序列化与
 *   竞态（实现类负责加锁）。
 * - [observeChanges] / [removeOnChangeListener] 的注册与注销应在同一线程或
 *   自行加锁，避免监听器集合并发修改。
 *
 * 生命周期：使用完毕（如模块卸载、Hook 卸载）应调用 [close] 释放监听器与
 * 远端引用，防止内存泄漏与 Binder 链路残留。
 */
interface IConfigRepository {

    /**
     * 加载当前配置。反序列化失败时实现应回退到 [AthenaConfig.DEFAULT]，
     * **不得**抛出异常导致宿主进程崩溃。
     */
    fun load(): AthenaConfig

    /**
     * 保存配置。Hook 进程的远端实现会抛出 [UnsupportedOperationException]。
     */
    fun save(config: AthenaConfig)

    /**
     * 注册配置变更监听器。底层基于 SharedPreferences 的
     * `OnSharedPreferenceChangeListener`，[listener] 会在配置真正发生变化后
     * 被回调，参数为变更后的最新 [AthenaConfig]。
     */
    fun observeChanges(listener: (AthenaConfig) -> Unit)

    /** 注销通过 [observeChanges] 注册的监听器。重复注销或注销未注册的监听器为 no-op。 */
    fun removeOnChangeListener(listener: (AthenaConfig) -> Unit)

    /** 释放底层资源（监听器、Binder 引用等）。 */
    fun close()
}

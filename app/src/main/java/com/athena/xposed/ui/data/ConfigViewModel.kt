package com.athena.xposed.ui.data

import android.content.Context
import androidx.lifecycle.ViewModel
import com.athena.xposed.data.LocalConfigRepository
import com.athena.xposed.model.AppEntry
import com.athena.xposed.model.AthenaConfig
import com.athena.xposed.model.DefaultPolicy
import com.athena.xposed.model.EntrySet
import com.athena.xposed.model.ModuleConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 全局配置 ViewModel（单例）。
 *
 * 设计要点：
 *  - **单例**：作为 `object`，跨多个 Composable 共享同一份 StateFlow，避免每个
 *    页面各自反序列化 SharedPreferences 造成状态分裂。
 *  - **不可变快照**：对外暴露 [state] 为 [StateFlow]<[AthenaConfig]>，每次更新
 *    整体替换为新实例，配合 Compose 的 `collectAsStateWithLifecycle()` 实现细粒度
 *    重组。
 *  - **线程安全**：所有 SharedPreferences 读写通过 [Dispatchers.IO] 调度，
 *    UI 线程仅消费 [StateFlow] 快照；[update] 使用 [Mutex] 串行化并发写入，
 *    避免丢失中间状态。
 *  - **生命周期**：内部 [scope] 使用 [SupervisorJob] + [Dispatchers.Main.immediate]，
 *    作为应用级协程作用域，与单例生命周期一致，不会随 Activity 销毁而取消。
 *
 * 必须在 [com.athena.xposed.AthenaApplication.onCreate] 中调用 [init] 完成
 * Repository 初始化后再使用，否则访问 [state] 仅会拿到默认配置。
 */
object ConfigViewModel : ViewModel() {

    private lateinit var repository: LocalConfigRepository

    private val _state: MutableStateFlow<AthenaConfig> = MutableStateFlow(AthenaConfig.DEFAULT)
    val state: StateFlow<AthenaConfig> = _state.asStateFlow()

    /**
     * 应用级协程作用域，替代 [viewModelScope]。
     * 单例模式下 viewModelScope 永不取消，显式使用此 scope 避免语义歧义。
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * [update] 的串行化 Mutex，避免并发 update 丢失中间状态。
     */
    private val updateMutex = Mutex()

    /**
     * 是否正在首次加载中。为 true 时忽略 SP 监听器回调，
     * 避免 load() 与 observeChanges 回调重复写入 _state。
     */
    private var initialLoading = false

    /**
     * 初始化 Repository 并加载已持久化的配置。
     *
     * 多次调用安全：仅在首次调用时执行实际初始化。
     */
    fun init(context: Context) {
        if (::repository.isInitialized) return
        repository = LocalConfigRepository(context.applicationContext)
        initialLoading = true
        // 注册一次 SharedPreferences 监听，使外部（如 Hook 进程触发的远端同步）的写入
        // 也能反映到 UI。load() 不再重复注册，避免监听器泄漏。
        repository.observeChanges { newCfg ->
            if (!initialLoading) {
                _state.value = newCfg
            }
        }
        load()
    }

    /**
     * 同步加载一次配置并更新 [state]。
     *
     * IO 由 [Dispatchers.IO] 承载，避免阻塞 UI 线程。
     */
    fun load() {
        if (!::repository.isInitialized) return
        scope.launch(Dispatchers.IO) {
            val cfg = runCatching { repository.load() }.getOrDefault(AthenaConfig.DEFAULT)
            _state.value = cfg
            initialLoading = false
        }
    }

    /**
     * 以不可变「读取-变换-写回」方式更新配置。
     *
     * [transform] 接收当前快照，返回新快照；本方法在 [Dispatchers.IO] 上完成
     * 持久化，并更新 [state]。使用 [Mutex] 串行化并发调用，避免两个并发
     * update 读到相同 current 导致后写入覆盖前者。
     */
    suspend fun update(transform: (AthenaConfig) -> AthenaConfig) {
        if (!::repository.isInitialized) return
        updateMutex.withLock {
            withContext(Dispatchers.IO) {
                val current = _state.value
                val next = transform(current)
                runCatching { repository.save(next) }
                _state.value = next
            }
        }
    }

    // ---- 模块全局配置 helper ----

    /** 设置模块总开关。 */
    fun setGlobalEnabled(enabled: Boolean) {
        scope.launch {
            update { it.copy(module = it.module.copy(globalEnabled = enabled)) }
        }
    }

    /** 设置默认策略。 */
    fun setDefaultPolicy(policy: DefaultPolicy) {
        scope.launch {
            update { it.copy(module = it.module.copy(defaultPolicy = policy)) }
        }
    }

    /** 设置默认冻结超时（毫秒）。 */
    fun setDefaultFreezeTimeoutMs(ms: Long) {
        scope.launch {
            update { it.copy(module = it.module.copy(defaultFreezeTimeoutMs = ms.coerceAtLeast(0L))) }
        }
    }

    /** 设置调试日志开关。 */
    fun setDebugLog(enabled: Boolean) {
        scope.launch {
            update { it.copy(module = it.module.copy(debugLog = enabled)) }
        }
    }

    /** 设置 native 文件注入开关。 */
    fun setNativeFileInjection(enabled: Boolean) {
        scope.launch {
            update { it.copy(module = it.module.copy(nativeFileInjection = enabled)) }
        }
    }

    /**
     * 对 [ModuleConfig] 做整体变换，便于 UI 中组合多个字段同时修改的场景。
     */
    fun updateModuleConfig(transform: (ModuleConfig) -> ModuleConfig) {
        scope.launch {
            update { it.copy(module = transform(it.module)) }
        }
    }

    // ---- 白名单 helper ----

    /** 向白名单添加一个条目；已存在则覆盖。 */
    fun addToWhiteList(entry: AppEntry) {
        scope.launch {
            update { cfg ->
                val newSet = EntrySet(cfg.whiteList.entries.toMutableList()).apply { add(entry) }
                cfg.copy(whiteList = newSet)
            }
        }
    }

    /** 按包名从白名单移除条目。 */
    fun removeFromWhiteList(packageName: String) {
        scope.launch {
            update { cfg ->
                val newSet = EntrySet(cfg.whiteList.entries.toMutableList()).apply { remove(packageName) }
                cfg.copy(whiteList = newSet)
            }
        }
    }

    // ---- 黑名单 helper ----

    /** 向黑名单添加一个条目；已存在则覆盖。 */
    fun addToBlackList(entry: AppEntry) {
        scope.launch {
            update { cfg ->
                val newSet = EntrySet(cfg.blackList.entries.toMutableList()).apply { add(entry) }
                cfg.copy(blackList = newSet)
            }
        }
    }

    /** 按包名从黑名单移除条目。 */
    fun removeFromBlackList(packageName: String) {
        scope.launch {
            update { cfg ->
                val newSet = EntrySet(cfg.blackList.entries.toMutableList()).apply { remove(packageName) }
                cfg.copy(blackList = newSet)
            }
        }
    }

    // ---- 条目级 helper ----

    /**
     * 切换指定列表中某条目的 enabled 状态。
     *
     * @param inWhitelist true 操作白名单，false 操作黑名单。
     */
    fun toggleEntryEnabled(packageName: String, inWhitelist: Boolean) {
        scope.launch {
            update { cfg ->
                if (inWhitelist) {
                    val newSet = toggleEntryInSet(cfg.whiteList, packageName)
                    cfg.copy(whiteList = newSet)
                } else {
                    val newSet = toggleEntryInSet(cfg.blackList, packageName)
                    cfg.copy(blackList = newSet)
                }
            }
        }
    }

    /** 在 [EntrySet] 中切换某条目 enabled 状态，返回新的 [EntrySet]。 */
    private fun toggleEntryInSet(set: EntrySet, packageName: String): EntrySet {
        val list = set.entries.toMutableList()
        val idx = list.indexOfFirst { it.packageName == packageName }
        if (idx >= 0) {
            val e = list[idx]
            list[idx] = e.copy(enabled = !e.enabled)
        }
        return EntrySet(list)
    }
}

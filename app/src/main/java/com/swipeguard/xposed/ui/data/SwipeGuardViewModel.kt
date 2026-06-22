package com.swipeguard.xposed.ui.data

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swipeguard.xposed.data.LocalConfigRepository
import com.swipeguard.xposed.model.SwipeGuardConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 单例 ViewModel，管理 SwipeGuard 配置状态。
 *
 * 双层白名单架构：
 * - [systemDefaults]：从 ColorOS 原始 XML 提取的 OEM 预设白名单（由 Hook 进程写入）
 * - [config.userAdditions] / [config.userRemovals]：用户的增删操作
 * - [UiState.effectiveProtectedApps]：有效白名单 = (systemDefaults - userRemovals) + userAdditions
 */
object SwipeGuardViewModel : ViewModel() {

    data class UiState(
        val config: SwipeGuardConfig = SwipeGuardConfig.DEFAULT,
        val systemDefaults: Set<String> = emptySet(),
    ) {
        /** 有效白名单 = 系统默认 - 用户移除 + 用户添加 */
        val effectiveProtectedApps: Set<String>
            get() = (systemDefaults - config.userRemovals) + config.userAdditions
    }

    private lateinit var repository: LocalConfigRepository
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun init(app: Application) {
        if (::repository.isInitialized) return
        repository = LocalConfigRepository(app)

        // 订阅配置和系统默认白名单变更（跨进程热更新）
        // observeChanges 监听所有 key，当 config 或 system_defaults 变化时
        // 重新加载完整状态
        repository.observeChanges { _ -> load() }

        load()
    }

    fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            val config = repository.load()
            val defaults = repository.loadSystemDefaults()
            _state.value = UiState(
                config = config,
                systemDefaults = defaults,
            )
        }
    }

    fun toggleEnabled() {
        viewModelScope.launch(Dispatchers.IO) {
            val current = _state.value.config
            val next = current.copy(enabled = !current.enabled)
            repository.save(next)
            _state.value = _state.value.copy(config = next)
        }
    }

    /**
     * 添加包名到白名单。
     * 如果是之前从系统默认中移除的包，则从 [userRemovals] 中恢复。
     */
    fun addPackage(pkg: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = _state.value
            val nextConfig = current.config.copy(
                userAdditions = current.config.userAdditions + pkg,
                userRemovals = current.config.userRemovals - pkg  // 如果之前被移除则恢复
            )
            repository.save(nextConfig)
            _state.value = current.copy(config = nextConfig)
        }
    }

    /**
     * 从白名单中移除包名。
     * - 如果是系统默认 → 加入 [userRemovals]（标记移除）
     * - 如果是用户添加 → 从 [userAdditions] 中删除
     */
    fun removePackage(pkg: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = _state.value
            val nextConfig = if (pkg in current.systemDefaults) {
                // 是系统默认 → 标记移除
                current.config.copy(userRemovals = current.config.userRemovals + pkg)
            } else {
                // 是用户添加 → 直接删除
                current.config.copy(userAdditions = current.config.userAdditions - pkg)
            }
            repository.save(nextConfig)
            _state.value = current.copy(config = nextConfig)
        }
    }
}

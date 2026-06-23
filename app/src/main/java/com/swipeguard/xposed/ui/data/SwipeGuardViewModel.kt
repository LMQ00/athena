package com.swipeguard.xposed.ui.data

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swipeguard.xposed.data.LocalConfigRepository
import com.swipeguard.xposed.data.SwipeGuardMcpClient
import com.swipeguard.xposed.model.SwipeGuardConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 单例 ViewModel，管理 SwipeGuard 配置状态。
 *
 * 有效白名单配置在 [SwipeGuardConfig] 中统一管理：
 * - [SwipeGuardConfig.systemDefaults]：从 ColorOS 原始 XML 提取的 OEM 预设白名单
 *   （UI 进程通过 MCP 从系统 Athena APK 读取）
 * - [SwipeGuardConfig.userAdditions] / [SwipeGuardConfig.userRemovals]：用户的增删操作
 * - [UiState.effectiveProtectedApps]：有效白名单 = (systemDefaults - userRemovals) + userAdditions
 */
object SwipeGuardViewModel : ViewModel() {

    data class UiState(
        val config: SwipeGuardConfig = SwipeGuardConfig.DEFAULT,
    ) {
        /** 有效白名单 = 系统默认 - 用户移除 + 用户添加 */
        val effectiveProtectedApps: Set<String>
            get() = config.effectiveProtectedApps
    }

    private lateinit var repository: LocalConfigRepository
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun init(app: Application) {
        if (::repository.isInitialized) return
        repository = LocalConfigRepository(app)

        // 订阅配置变更（跨进程热更新），systemDefaults 已内嵌在 config JSON 中
        repository.observeChanges { _ -> load() }

        load()
    }

    fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            var config = repository.load()
            // 如果 systemDefaults 为空，尝试从 MCP 读取
            if (config.systemDefaults.isEmpty()) {
                val defaults = SwipeGuardMcpClient.loadSystemDefaults()
                if (defaults.isNotEmpty()) {
                    config = config.copy(systemDefaults = defaults)
                    repository.save(config)
                }
            }
            _state.value = UiState(config = config)
        }
    }

    /** 用户手动刷新系统默认白名单（从 MCP 读取） */
    fun refreshSystemDefaults() {
        viewModelScope.launch(Dispatchers.IO) {
            val defaults = SwipeGuardMcpClient.loadSystemDefaults()
            if (defaults.isNotEmpty()) {
                val config = _state.value.config.copy(systemDefaults = defaults)
                repository.save(config)
                _state.value = UiState(config = config)
            }
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
            val current = _state.value.config
            val nextConfig = if (pkg in current.systemDefaults) {
                // 是系统默认 → 标记移除
                current.copy(userRemovals = current.userRemovals + pkg)
            } else {
                // 是用户添加 → 直接删除
                current.copy(userAdditions = current.userAdditions - pkg)
            }
            repository.save(nextConfig)
            _state.value = UiState(config = nextConfig)
        }
    }
}

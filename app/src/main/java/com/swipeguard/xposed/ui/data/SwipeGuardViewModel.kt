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
 */
object SwipeGuardViewModel : ViewModel() {

    private lateinit var repository: LocalConfigRepository
    private val _state = MutableStateFlow(SwipeGuardConfig.DEFAULT)
    val state: StateFlow<SwipeGuardConfig> = _state.asStateFlow()

    fun init(app: Application) {
        if (::repository.isInitialized) return
        repository = LocalConfigRepository(app)
        load()
    }

    fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = repository.load()
        }
    }

    fun toggleEnabled() {
        viewModelScope.launch(Dispatchers.IO) {
            val next = _state.value.copy(enabled = !_state.value.enabled)
            repository.save(next)
            _state.value = next
        }
    }

    fun addPackage(pkg: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val next = _state.value.copy(
                protectedApps = _state.value.protectedApps + pkg
            )
            repository.save(next)
            _state.value = next
        }
    }

    fun removePackage(pkg: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val next = _state.value.copy(
                protectedApps = _state.value.protectedApps - pkg
            )
            repository.save(next)
            _state.value = next
        }
    }
}

package com.swipeguard.xposed

import android.app.Application

/**
 * SwipeGuard Application 入口。
 *
 * 职责仅限：初始化 UI 侧的配置仓储单例。
 * 不在 Hook 进程（:xposed_service）中初始化。
 */
class SwipeGuardApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 只在 UI 进程初始化，不在 :xposed_service 进程中初始化
        if (processName?.endsWith(":xposed_service") != true) {
            // 后续可在这里初始化 ViewModel 或 Config 仓储
        }
    }
}

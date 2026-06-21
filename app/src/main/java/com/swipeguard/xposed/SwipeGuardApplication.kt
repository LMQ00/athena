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
        // Application 入口（UI 进程）；Hook 进程由 ModuleMain 驱动，
        // 此处无需做进程隔离。后续可在此初始化 ViewModel / Config 仓储。
    }
}

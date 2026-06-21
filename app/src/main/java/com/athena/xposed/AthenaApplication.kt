package com.athena.xposed

import android.app.Application
import com.athena.xposed.ui.data.ConfigViewModel

/**
 * Athena Application 入口。
 *
 * 仅承担两项职责：
 *  1. 在进程启动时初始化 [ConfigViewModel] 单例，注入 ApplicationContext，
 *     使其内部 [com.athena.xposed.data.LocalConfigRepository] 拿到长生命周期
 *     Context，避免 Activity 泄漏；
 *  2. 作为未来模块级初始化（如全局日志、崩溃捕获）的统一入口。
 *
 * 在 `AndroidManifest.xml` 的 `<application android:name=".AthenaApplication"/>`
 * 中注册。
 */
class AthenaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ConfigViewModel.init(this)
    }
}

package com.swipeguard.xposed.hook

import android.util.Log
import com.swipeguard.xposed.data.RemoteConfigRepository
import com.swipeguard.xposed.model.SwipeGuardConfig
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * 划卡杀进程拦截 Hook。
 *
 * 拦截 [ActivityManagerService.killBackgroundProcesses] 方法。
 * 当被杀的包名在 SwipeGuard 白名单中时，跳过 kill。
 *
 * killBackgroundProcesses(String packageName, int userId, int reason)
 * 参数：pkg 直接是包名，无需反查 uid → 简单可靠。
 */
class SwipeKillHooks(private val module: XposedModule) {

    private var config: SwipeGuardConfig = SwipeGuardConfig.DEFAULT
    private val tag = "SwipeGuard/SwipeKill"

    /** 从配置仓储同步配置 */
    fun syncConfig(repo: RemoteConfigRepository) {
        config = repo.load()
    }

    /** 安装 Hook */
    fun install() {
        try {
            val amsClass = Class.forName("com.android.server.am.ActivityManagerService")
            val method = amsClass.declaredMethods.firstOrNull { m ->
                m.name == "killBackgroundProcesses" &&
                m.parameterCount >= 1 &&
                m.parameterTypes[0] == String::class.java
            } ?: run {
                module.log(Log.WARN, tag, "killBackgroundProcesses method not found")
                return
            }

            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val pkg = chain.getArg(0) as? String
                    if (pkg != null && config.enabled && pkg in config.protectedApps) {
                        module.log(Log.INFO, tag, "Blocked kill for protected app: $pkg")
                        return@intercept null  // 跳过原始方法，不杀进程
                    }
                    chain.proceed()  // 不在白名单，正常杀
                }
            
            module.log(Log.INFO, tag, "SwipeKill hook installed")
        } catch (t: Throwable) {
            module.log(Log.ERROR, tag, "Failed to install SwipeKill hook: ${t.message}")
        }
    }
}

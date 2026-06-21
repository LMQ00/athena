package com.swipeguard.xposed.hook

import android.util.Log
import com.swipeguard.xposed.data.RemoteConfigRepository
import com.swipeguard.xposed.model.SwipeGuardConfig
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * 划卡杀进程拦截 Hook。
 *
 * 拦截 [ActivityManagerService] 的杀进程方法。
 * 当被杀的包名在 SwipeGuard 白名单中时，跳过 kill。
 *
 * 双路径保护：
 * 1. killBackgroundProcesses(String, int, int) — AOSP 标准划卡杀路径
 * 2. forceStopPackage(String, int) — ColorOS / OEM 备用杀路径
 *
 * 参数：pkg 直接是包名，无需反查 uid → 简单可靠。
 */
class SwipeKillHooks(private val module: XposedModule) {

    private var config: SwipeGuardConfig = SwipeGuardConfig.DEFAULT
    private val tag = "SwipeGuard/SwipeKill"

    /** 从配置仓储同步配置 */
    fun syncConfig(repo: RemoteConfigRepository) {
        config = repo.load()
    }

    /** 安装 Hook（双路径） */
    fun install() {
        hookKillBackgroundProcesses()
        hookForceStopPackage()
    }

    /**
     * 路径 1: ActivityManagerService.killBackgroundProcesses
     * 标椎划卡杀路径，被杀包名直接传进来。
     */
    private fun hookKillBackgroundProcesses() {
        try {
            val amsClass = Class.forName("com.android.server.am.ActivityManagerService")
            val method = amsClass.declaredMethods.firstOrNull { m ->
                m.name == "killBackgroundProcesses" &&
                m.parameterCount >= 1 &&
                m.parameterTypes[0] == String::class.java
            } ?: run {
                module.log(Log.WARN, tag, "killBackgroundProcesses not found, skip")
                return
            }

            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val pkg = chain.getArg(0) as? String
                    if (shouldProtect(pkg)) {
                        module.log(Log.INFO, tag, "Blocked killBackgroundProcesses for $pkg")
                        return@intercept null
                    }
                    chain.proceed()
                }

            module.log(Log.INFO, tag, "Hook installed: killBackgroundProcesses")
        } catch (t: Throwable) {
            module.log(Log.ERROR, tag, "killBackgroundProcesses hook failed: ${t.message}")
        }
    }

    /**
     * 路径 2: ActivityManagerService.forceStopPackage
     * ColorOS 等 OEM 有时用此路径替代 killBackgroundProcesses。
     */
    private fun hookForceStopPackage() {
        try {
            val amsClass = Class.forName("com.android.server.am.ActivityManagerService")
            val method = amsClass.declaredMethods.firstOrNull { m ->
                m.name == "forceStopPackage" &&
                m.parameterCount >= 1 &&
                m.parameterTypes[0] == String::class.java
            } ?: run {
                module.log(Log.WARN, tag, "forceStopPackage not found, skip")
                return
            }

            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val pkg = chain.getArg(0) as? String
                    if (shouldProtect(pkg)) {
                        module.log(Log.INFO, tag, "Blocked forceStopPackage for $pkg")
                        return@intercept null
                    }
                    chain.proceed()
                }

            module.log(Log.INFO, tag, "Hook installed: forceStopPackage")
        } catch (t: Throwable) {
            module.log(Log.ERROR, tag, "forceStopPackage hook failed: ${t.message}")
        }
    }

    /** 判断包名是否受保护。 */
    private fun shouldProtect(pkg: String?): Boolean =
        pkg != null && config.enabled && pkg in config.protectedApps
}

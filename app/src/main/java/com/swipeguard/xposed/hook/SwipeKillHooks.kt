package com.swipeguard.xposed.hook

import android.util.Log
import com.swipeguard.xposed.data.RemoteConfigRepository
import com.swipeguard.xposed.model.SwipeGuardConfig
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * 划卡杀进程拦截 Hook。
 *
 * 逆向 Athena 6.0.1 APK 发现：
 * - Athena 使用自有 API 而非 AOSP 标准方法
 * - 优先尝试 Hook athenaKill/clearProcess（在 system_server 的框架类上）
 * - 失败则回退到 AOSP killBackgroundProcesses/forceStopPackage
 */
class SwipeKillHooks(private val module: XposedModule) {

    private var config: SwipeGuardConfig = SwipeGuardConfig.DEFAULT
    private val tag = "SwipeGuard/AthenaKill"

    fun syncConfig(repo: RemoteConfigRepository) {
        config = repo.load()
    }

    fun install() {
        // 先试 Athena 自有 API
        if (!hookAthenaKill("athenaKill") &&
            !hookAthenaKill("athenaKill2") &&
            !hookAthenaKill("clearProcess")) {
            // 都不行 → 回退 AOSP
            module.log(Log.WARN, tag, "Athena API not found, fallback to AOSP")
            hookAospKillProcesses()
        }
    }

    /** 尝试在 system_server 框架类中 Hook athena 方法。 */
    private fun hookAthenaKill(methodName: String): Boolean {
        for (clsName in listOf(
            "com.oplus.athena.systemservice.OplusAthenaSystemService",
            "com.android.server.am.OplusAthenaAmManager",
            "oplus.app.AthenaServiceInternal"
        )) {
            try {
                val clz = Class.forName(clsName)
                for (m in clz.declaredMethods) {
                    if (m.name != methodName) continue
                    module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            val pkg = findPkgFromMethod(m, chain)
                            if (pkg != null && config.enabled && pkg in config.protectedApps) {
                                module.log(Log.INFO, tag, "Blocked $methodName for $pkg")
                                return@intercept null
                            }
                            chain.proceed()
                        }
                }
                module.log(Log.INFO, tag, "Hooked $clsName.$methodName")
                return true
            } catch (_: ClassNotFoundException) { }
              catch (_: Throwable) { }
        }
        return false
    }

    /** 从方法参数中提取包名。 */
    private fun findPkgFromMethod(method: java.lang.reflect.Method,
                                   chain: XposedInterface.HookChain): String? {
        try {
            val types = method.parameterTypes
            // 先找 String 参数（包名）
            for (i in types.indices) {
                if (types[i] == String::class.java) {
                    val v = chain.getArg(i) as? String
                    if (v != null && "." in v && !v.startsWith("android.")) return v
                }
            }
            // 再找 Bundle 参数
            for (i in types.indices) {
                if (types[i].name == "android.os.Bundle") {
                    val b = chain.getArg(i) as? android.os.Bundle ?: continue
                    for (k in listOf("pkg", "KEY_PKG_NAME", "caller_package")) {
                        b.getString(k)?.let { if ("." in it) return it }
                    }
                }
            }
        } catch (_: Throwable) { }
        return null
    }

    /** 回退：Hook AOSP 标准杀进程方法。 */
    private fun hookAospKillProcesses() {
        for (name in listOf("killBackgroundProcesses", "forceStopPackage")) {
            try {
                val ams = Class.forName("com.android.server.am.ActivityManagerService")
                val method = ams.getDeclaredMethod(name, String::class.java)
                module.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val pkg = chain.getArg(0) as? String
                        if (pkg != null && config.enabled && pkg in config.protectedApps) {
                            module.log(Log.INFO, tag, "AOSP blocked $name for $pkg")
                            return@intercept null
                        }
                        chain.proceed()
                    }
                module.log(Log.INFO, tag, "AOSP fallback hooked $name")
            } catch (_: Throwable) { }
        }
    }
}

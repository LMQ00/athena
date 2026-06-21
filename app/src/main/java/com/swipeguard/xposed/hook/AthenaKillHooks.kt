package com.swipeguard.xposed.hook

import android.util.Log
import com.swipeguard.xposed.data.RemoteConfigRepository
import com.swipeguard.xposed.model.SwipeGuardConfig
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * Athena 自有 API 拦截 Hook。
 *
 * 逆向 Athena 6.0.1 APK 发现它使用自有 API 而非 AOSP 标准方法。
 * 通过反射查找 system_server 中的实现类，Hook athenaKill/clearProcess。
 *
 * 设计原则：
 * - 完全独立于 SwipeKillHooks，互不影响
 * - 每个 Hook 都在独立 try-catch 中
 * - 所有反射操作容错，找不到类/方法就跳过
 */
class AthenaKillHooks(private val module: XposedModule) {

    private var config: SwipeGuardConfig = SwipeGuardConfig.DEFAULT
    private val tag = "SwipeGuard/AthenaKill"

    fun syncConfig(repo: RemoteConfigRepository) {
        config = repo.load()
    }

    fun install() {
        hookMethod("athenaKill")
        hookMethod("athenaKill2")
        hookMethod("clearProcess")
    }

    private fun hookMethod(methodName: String) {
        val candidates = listOf(
            "com.oplus.athena.systemservice.OplusAthenaSystemService",
            "com.android.server.am.OplusAthenaAmManager",
            "oplus.app.AthenaServiceInternal"
        )
        for (clsName in candidates) {
            try {
                val clz = Class.forName(clsName)
                var hooked = false
                for (m in clz.declaredMethods) {
                    if (m.name != methodName) continue
                    module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            if (shouldBlock(m, chain)) {
                                module.log(Log.INFO, tag, "Blocked $methodName")
                                return@intercept null
                            }
                            chain.proceed()
                        }
                    hooked = true
                }
                if (hooked) {
                    module.log(Log.INFO, tag, "Hooked $clsName.$methodName")
                    return
                }
            } catch (_: ClassNotFoundException) {
            } catch (_: Throwable) {
            }
        }
    }

    private fun shouldBlock(method: java.lang.reflect.Method,
                            chain: XposedInterface.HookChain): Boolean {
        if (!config.enabled) return false
        val pkg = findPkg(method, chain)
        return pkg != null && pkg in config.protectedApps
    }

    private fun findPkg(method: java.lang.reflect.Method,
                        chain: XposedInterface.HookChain): String? {
        try {
            val types = method.parameterTypes
            // 在所有 String 参数中找包名
            for (i in types.indices) {
                if (types[i] != String::class.java) continue
                val v = chain.getArg(i) as? String ?: continue
                if ("." in v && !v.startsWith("android.")) return v
            }
            // Bundle 参数中找
            for (i in types.indices) {
                if (types[i].name != "android.os.Bundle") continue
                val b = chain.getArg(i) as? android.os.Bundle ?: continue
                for (k in listOf("pkg", "KEY_PKG_NAME", "caller_package")) {
                    b.getString(k)?.let { if ("." in it) return it }
                }
            }
        } catch (_: Throwable) {
        }
        return null
    }
}

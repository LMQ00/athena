package com.swipeguard.xposed.hook

import android.util.Log
import com.swipeguard.xposed.data.RemoteConfigRepository
import com.swipeguard.xposed.model.SwipeGuardConfig
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * Athena 杀进程拦截 Hook。
 *
 * 逆向 Athena 6.0.1 APK 发现 Athena 使用自有 API 而非 AOSP 标准方法。
 * 动态查找实现类并 Hook athenaKill / clearProcess。
 */
class SwipeKillHooks(private val module: XposedModule) {

    private var config: SwipeGuardConfig = SwipeGuardConfig.DEFAULT
    private val tag = "SwipeGuard/AthenaKill"

    fun syncConfig(repo: RemoteConfigRepository) {
        config = repo.load()
    }

    fun install() {
        var n = 0
        for (m in listOf("athenaKill", "athenaKill2", "clearProcess")) {
            if (tryHookAthena(m)) n++
        }
        if (n == 0) {
            module.log(Log.WARN, tag, "Athena API not found, fallback to AOSP")
            fallbackAosp()
        } else {
            module.log(Log.INFO, tag, "Hooked $n Athena methods")
        }
    }

    private fun tryHookAthena(methodName: String): Boolean {
        for (cls in listOf(
            "com.oplus.athena.systemservice.OplusAthenaSystemService",
            "com.android.server.am.OplusAthenaAmManager",
            "oplus.app.AthenaServiceInternal",
        )) {
            try {
                val clz = Class.forName(cls)
                for (m in clz.declaredMethods) {
                    if (m.name != methodName) continue
                    doHook(m, methodName) { chain ->
                        val pkg = findPkg(m, chain)
                        if (pkg != null && config.enabled && pkg in config.protectedApps) {
                            module.log(Log.INFO, tag, "Blocked $methodName for $pkg")
                            null  // skip
                        } else {
                            chain.proceed()
                            Unit
                        }
                    }
                }
                return true
            } catch (_: ClassNotFoundException) { }
              catch (t: Throwable) {
                module.log(Log.DEBUG, tag, "$cls.$methodName: ${t.message}")
            }
        }
        return false
    }

    /** 拦截闭包：返回 null 跳过原方法，否则返回 proceed 结果。 */
    private fun doHook(method: Method, name: String, block: (XposedInterface.HookChain) -> Any?) {
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val r = block(chain)
                if (r == null) return@intercept defaultReturn(method.returnType)
                r
            }
        module.log(Log.INFO, tag, "Hooked $name")
    }

    /** 从参数中找包名。 */
    private fun findPkg(method: Method, chain: XposedInterface.HookChain): String? {
        try {
            val types = method.parameterTypes
            for (i in types.indices) {
                val t = types[i]
                if (t == String::class.java) {
                    val v = chain.getArg(i) as? String
                    if (v != null && "." in v && !v.startsWith("android.")) return v
                }
            }
            // Bundle 参数
            for (i in types.indices) {
                if (types[i].name == "android.os.Bundle") {
                    val b = chain.getArg(i) as? android.os.Bundle ?: continue
                    for (k in listOf("pkg", "KEY_PKG_NAME", "caller_package")) {
                        b.getString(k)?.let { if ("." in it && !it.startsWith("android.")) return it }
                    }
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun defaultReturn(ret: Class<*>): Any? {
        if (ret == Void.TYPE || ret.name == "void") return null
        if (ret == Int::class.javaPrimitiveType) return 0
        if (ret == Boolean::class.javaPrimitiveType) return false
        if (ret == Long::class.javaPrimitiveType) return 0L
        return null
    }

    /** 回退到标准 AOSP 方法。 */
    private fun fallbackAosp() {
        try {
            val ams = Class.forName("com.android.server.am.ActivityManagerService")
            for (name in listOf("killBackgroundProcesses", "forceStopPackage")) {
                try {
                    val m = ams.getDeclaredMethod(name, String::class.java)
                    doHook(m, name) { chain ->
                        val pkg = chain.getArg(0) as? String
                        if (pkg != null && config.enabled && pkg in config.protectedApps) {
                            null
                        } else {
                            chain.proceed()
                            Unit
                        }
                    }
                } catch (_: NoSuchMethodException) { }
            }
        } catch (_: ClassNotFoundException) { }
    }
}

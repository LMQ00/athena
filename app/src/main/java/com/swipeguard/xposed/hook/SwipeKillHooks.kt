package com.swipeguard.xposed.hook

import android.os.Bundle
import android.util.Log
import com.swipeguard.xposed.data.RemoteConfigRepository
import com.swipeguard.xposed.model.SwipeGuardConfig
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * Athena 杀进程拦截 Hook。
 *
 * 逆向 Athena 6.0.1 APK 发现：
 * - Athena 使用**自有 API**，不走 AOSP 标准 killBackgroundProcesses
 * - 核心 kill 方法在 IAthenaService AIDL 接口中：
 *   - athenaKill(int, int, String, int, int) — 单个杀
 *   - athenaKill2(int, int, String, int, int) — 变体
 *   - athenaKill3(List) — 批量杀
 *   - clearProcess(Bundle) — Bundle 参数清进程
 *
 * 由于实际实现在 framework JAR (OplusAthenaSystemService) 中，
 * 通过 Class.forName 动态查找并 Hook。
 */
class SwipeKillHooks(private val module: XposedModule) {

    private var config: SwipeGuardConfig = SwipeGuardConfig.DEFAULT
    private val tag = "SwipeGuard/AthenaKill"

    fun syncConfig(repo: RemoteConfigRepository) {
        config = repo.load()
    }

    fun install() {
        val installed = listOf(
            hookAthenaMethod("athenaKill"),
            hookAthenaMethod("athenaKill2"),
            hookAthenaMethod("clearProcess")
        ).count { it }

        if (installed == 0) {
            module.log(Log.WARN, tag, "Athena API not found, fallback to AOSP methods")
            hookAospMethods()
        } else {
            module.log(Log.INFO, tag, "Installed $installed Athena kill hooks")
        }
    }

    /**
     * 尝试在候选实现类中 Hook Athena 方法。
     */
    private fun hookAthenaMethod(methodName: String): Boolean {
        val candidates = listOf(
            "com.oplus.athena.systemservice.OplusAthenaSystemService",
            "com.android.server.am.OplusAthenaAmManager",
            "oplus.app.AthenaServiceInternal",
            "com.oplus.athena.systemservice.transact.h",
        )

        for (clsName in candidates) {
            try {
                val clazz = Class.forName(clsName)
                val methods = clazz.declaredMethods.filter { it.name == methodName }
                if (methods.isEmpty()) continue

                for (method in methods) {
                    module.hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            val pkg = extractPkg(method, chain)
                            if (shouldProtect(pkg)) {
                                module.log(Log.INFO, tag, "Blocked $methodName for $pkg")
                                return@intercept defaultReturn(method.returnType)
                            }
                            chain.proceed()
                        }
                }
                module.log(Log.INFO, tag, "Hooked $clsName.$methodName (${methods.size})")
                return true
            } catch (_: ClassNotFoundException) { continue }
              catch (t: Throwable) {
                module.log(Log.DEBUG, tag, "$clsName.$methodName: ${t.message}")
            }
        }
        return false
    }

    /**
     * 从方法参数中提取包名。
     */
    private fun extractPkg(method: Method, chain: XposedInterface.HookChain): String? {
        try {
            val params = method.parameterTypes
            // athenaKill(..., String pkgName, ...)
            for (i in params.indices) {
                if (params[i] == String::class.java) {
                    val arg = chain.getArg(i) as? String
                    if (arg != null && "." in arg) return arg
                }
            }
            // clearProcess(Bundle bundle)
            for (i in params.indices) {
                if (params[i] == Bundle::class.java) {
                    val b = chain.getArg(i) as? Bundle ?: continue
                    b.getString("pkg")?.let { if ("." in it) return it }
                    b.getString("KEY_PKG_NAME")?.let { if ("." in it) return it }
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun defaultReturn(ret: Class<*>): Any? = when (ret) {
        Int::class.javaPrimitiveType -> 0
        Boolean::class.javaPrimitiveType -> false
        Long::class.javaPrimitiveType -> 0L
        Void.TYPE -> null
        else -> null
    }

    private fun shouldProtect(pkg: String?): Boolean =
        pkg != null && config.enabled && pkg in config.protectedApps

    /**
     * 回退：标准 AOSP 杀进程方法。
     */
    private fun hookAospMethods() {
        val aosp = listOf(
            "killBackgroundProcesses" to listOf(String::class.java),
            "forceStopPackage" to listOf(String::class.java),
        )
        for ((name, params) in aosp) {
            try {
                val clazz = Class.forName("com.android.server.am.ActivityManagerService")
                val m = clazz.getDeclaredMethod(name, *params.toTypedArray())
                module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val pkg = chain.getArg(0) as? String
                        if (shouldProtect(pkg)) {
                            module.log(Log.INFO, tag, "AOSP blocked $name for $pkg")
                            return@intercept null
                        }
                        chain.proceed()
                    }
                module.log(Log.INFO, tag, "AOSP fallback hooked $name")
            } catch (_: Throwable) {}
        }
    }
}

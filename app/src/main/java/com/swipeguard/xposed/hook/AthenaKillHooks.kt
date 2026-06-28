package com.swipeguard.xposed.hook

import android.util.Log
import com.swipeguard.xposed.data.RemoteConfigRepository
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

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
class AthenaKillHooks(private val module: XposedModule,
                      private val classLoader: ClassLoader) {

    @Volatile
    private var effectiveSet: Set<String> = emptySet()
    @Volatile
    private var enabled: Boolean = true
    private val tag = "SwipeGuard/AthenaKill"

    fun syncConfig(repo: RemoteConfigRepository) {
        val cfg = repo.load()
        enabled = cfg.enabled
        effectiveSet = cfg.effectiveProtectedApps
    }

    fun install() {
        hookMethod("athenaKill")
        hookMethod("athenaKill2")
        hookMethod("athenaKill3")   // 新版批量 kill（code 201）
        hookMethod("clearProcess")
        module.log(
            Log.INFO, tag,
            "Install complete. effectiveSet size=${effectiveSet.size}, enabled=$enabled"
        )
    }

    private fun hookMethod(methodName: String) {
        val candidates = listOf(
            // RemoteService 是实际包含 athenaKill/clearProcess 的 IAthenaService.Stub 实现类
            // 运行在 system 进程（与 system_server 同进程），逆向 Athena 6.0.1 确认
            "com.oplus.athena.systemservice.transact.RemoteService",
            "com.oplus.athena.systemservice.OplusAthenaSystemService",
            "com.android.server.am.OplusAthenaAmManager",
            "oplus.app.AthenaServiceInternal",
            // 新版 / 备用名
            "com.oplus.athena.systemservice.h1",
            "com.oplus.athena.systemservice.transact.KillService",
            "com.oplus.athena.systemservice.KillManagerService",
        )
        for (clsName in candidates) {
            try {
                val clz = Class.forName(clsName, false, classLoader)
                var hooked = false
                for (m in clz.declaredMethods) {
                    if (m.name != methodName) continue
                    module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            if (shouldBlock(m, chain)) {
                                module.log(Log.INFO, tag, "Blocked $methodName")
                                return@intercept 0
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
                module.log(Log.WARN, tag, "Class $clsName not found for $methodName, trying next")
            } catch (t: Throwable) {
                module.log(Log.WARN, tag, "Failed to hook $clsName.$methodName: ${t.message}")
            }
        }
        module.log(Log.WARN, tag, "ALL candidates failed for $methodName — no Athena kill interception")
    }

    private fun shouldBlock(method: Method,
                            chain: XposedInterface.Chain): Boolean {
        if (!enabled) return false
        val pkg = findPkg(method, chain)
        return pkg != null && pkg in effectiveSet
    }

    private fun findPkg(method: Method,
                        chain: XposedInterface.Chain): String? {
        try {
            val types = method.parameterTypes
            // 在所有 String 参数中找包名
            for (i in types.indices) {
                if (types[i] != String::class.java) continue
                val v = chain.getArg(i) as? String ?: continue
                if (isValidPackageName(v)) return v
            }
            // Bundle 参数中找
            for (i in types.indices) {
                if (types[i].name != "android.os.Bundle") continue
                val b = chain.getArg(i) as? android.os.Bundle ?: continue
                for (k in listOf("pkg", "KEY_PKG_NAME", "caller_package",
                        "packageName", "package_name", "killPkg")) {
                    b.getString(k)?.let { if (isValidPackageName(it)) return it }
                }
            }
        } catch (_: Throwable) {
        }
        return null
    }

    /**
     * 判断字符串是否为有效的 Android 包名。
     * 规则：至少包含一个点，不以 "android." 或 "java." 开头，
     * 不包含空格/控制字符，不是 IP 地址或文件路径。
     */
    private fun isValidPackageName(s: String): Boolean {
        if (s.length < 3 || s.length > 255) return false
        if (!s.contains('.')) return false
        if (s.startsWith("android.") || s.startsWith("java.") ||
            s.startsWith("dalvik.") || s.startsWith("com.android.")) return false
        // 排除 IP 地址、文件路径等非包名
        if (s.matches(Regex("""\d+(\.\d+)+"""))) return false  // IP 地址
        if (s.startsWith("/") || s.startsWith("\\")) return false  // 文件路径
        if (s.contains(' ')) return false  // 含空格
        return true
    }
}

package com.swipeguard.xposed.hook

import android.os.Bundle
import android.os.Parcel
import android.util.Log
import com.swipeguard.xposed.data.RemoteConfigRepository
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * Athena Binder 入口拦截 Hook。
 *
 * Hook [IAthenaService.Stub.onTransact] 以在 Binder 入口处拦截划卡杀进程。
 * 相比 SwipeKillHooks 在 kill 执行器层面拦截，此 Hook 在 Binder 请求分发阶段
 * 就终止调用链，更早也更可靠。
 *
 * 此 Hook 在 `com.oplus.athena` 进程中安装（不同于 system_server 侧的 SwipeKillHooks），
 * 通过 [ModuleMain.onPackageLoaded] 触发。
 *
 * 拦截的 Binder code：
 * - 223 = clearProcess：划卡清理入口，Bundle 包含 packageName
 * - 201 = athenaKill3：批量 kill，List<Bundle> 逐个检查
 */
class AthenaBinderHooks(
    private val module: XposedModule,
    private val classLoader: ClassLoader
) {
    @Volatile
    private var enabled: Boolean = true
    @Volatile
    private var effectiveSet: Set<String> = emptySet()
    private val tag = "SwipeGuard/AthenaBinder"

    fun syncConfig(repo: RemoteConfigRepository) {
        val cfg = repo.load()
        enabled = cfg.enabled
        effectiveSet = cfg.effectiveProtectedApps
    }

    fun install() {
        try {
            // 查找 IAthenaService$Stub（Binder 服务端 Stub，处理 onTransact 分发）
            val stubClass = try {
                Class.forName("com.oplus.app.IAthenaService\$Stub", false, classLoader)
            } catch (_: ClassNotFoundException) {
                module.log(Log.WARN, tag, "IAthenaService.Stub not found, skip Binder hook")
                null
            }

            if (stubClass != null) {
                val onTransact = try {
                    stubClass.getDeclaredMethod(
                        "onTransact",
                        Int::class.javaPrimitiveType,
                        Parcel::class.java,
                        Parcel::class.java,
                        Int::class.javaPrimitiveType
                    )
                } catch (_: NoSuchMethodException) {
                    module.log(Log.WARN, tag, "IAthenaService.Stub.onTransact method not found")
                    null
                }

                if (onTransact != null) {
                    module.hook(onTransact)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            if (!enabled) return@intercept chain.proceed()
                            val code = chain.getArg(0) as? Int ?: return@intercept chain.proceed()
                            when (code) {
                                223 -> handleClearProcess(chain)
                                201 -> handleAthenaKill3(chain)
                                else -> chain.proceed()
                            }
                        }
                    module.log(Log.INFO, tag, "IAthenaService.Stub.onTransact hooked")
                    return
                }
            }

            module.log(Log.WARN, tag, "AthenaBinderHooks: all hooks failed to install")
        } catch (t: Throwable) {
            module.log(Log.ERROR, tag, "AthenaBinderHooks install failed: ${t.message}")
        }
    }

    /**
     * 处理 clearProcess (Binder code 223) 调用。
     * 从 data Parcel 中还原 Bundle，提取 packageName 检查白名单。
     * 白名单包名 → 拦截调用、写 reply 并返回 true（Binder 已处理语义）。
     */
    private fun handleClearProcess(chain: XposedInterface.Chain): Any? {
        try {
            val data = chain.getArg(1) as? Parcel ?: return chain.proceed()
            val startPos = data.dataPosition()
            data.setDataPosition(0)
            val bundle = try {
                data.readBundle(classLoader)
            } finally {
                data.setDataPosition(startPos)
            }
            if (bundle != null) {
                val pkg = bundle.getString("packageName")
                    ?: bundle.getString("pkg")
                    ?: bundle.getString("KEY_PKG_NAME")
                if (pkg != null && pkg in effectiveSet) {
                    module.log(Log.INFO, tag, "Blocked clearProcess for $pkg")
                    val reply = chain.getArg(2) as? Parcel
                    reply?.writeNoException()
                    return true
                }
            }
        } catch (_: Throwable) {
            // Parcel 读取异常 → 放行，避免误杀正常调用
        }
        return chain.proceed()
    }

    /**
     * 处理 athenaKill3 (Binder code 201) 调用。
     * 从 data Parcel 中还原 List<Bundle>，逐个检查包名。
     * 有任意白名单包名 → 记录日志并放行（由调用方决定是否全量拦截或部分拦截）。
     *
     * 当前策略：仅做日志记录，不拦截 athenaKill3。原因是批量 kill 中
     * 可能混有白名单和非白名单包，全部拦截会阻止合法清理。
     */
    private fun handleAthenaKill3(chain: XposedInterface.Chain): Any? {
        try {
            val data = chain.getArg(1) as? Parcel ?: return chain.proceed()
            val startPos = data.dataPosition()
            data.setDataPosition(0)
            val bundleList = try {
                data.readArrayList(classLoader) as? List<*>
            } finally {
                data.setDataPosition(startPos)
            }
            if (bundleList != null) {
                val blockedPkgs = mutableListOf<String>()
                for (item in bundleList) {
                    val bundle = item as? Bundle ?: continue
                    val pkg = bundle.getString("packageName")
                        ?: bundle.getString("pkg")
                        ?: continue
                    if (pkg in effectiveSet) blockedPkgs.add(pkg)
                }
                if (blockedPkgs.isNotEmpty()) {
                    module.log(
                        Log.INFO, tag,
                        "athenaKill3 contains protected: ${blockedPkgs.joinToString()} (passing through)"
                    )
                }
            }
        } catch (_: Throwable) {
            // Parcel 解析异常 → 放行
        }
        return chain.proceed()
    }
}

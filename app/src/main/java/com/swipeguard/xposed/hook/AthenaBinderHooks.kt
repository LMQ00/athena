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
 * 通过 [ModuleMain.onPackageReady] 触发。
 *
 * 逆向 Athena 6.0.1 确认的 Binder transact code（IAthenaService AIDL + 编译确认）：
 *
 * AIDL 源文件（从 APK 中提取）声明 codes 为 athenaKill=100, athenaFreeze=101,
 * athenaKill2=102, athenaKill3=201, clearProcess=223。但 ColorOS AIDL 编译器
 * 在生成 Stub 时对显式赋值的 code 加上了 IBinder.FIRST_CALL_TRANSACTION(=1)
 * 的偏移，因此实际运行时使用的 code 比 AIDL 声明值大 1。
 *
 * 实际 Binder code（从 Smali .field 声明确认）：
 * - 101 (0x65) = athenaKill（旧版单包杀，已废弃）
 * - 102 (0x66) = athenaFreeze（冻结）
 * - 103 (0x67) = athenaKill2（新版单包杀，6 参数）
 * - 202 (0xca) = athenaKill3（新版批量 kill，List<Bundle>）
 * - 224 (0xe0) = clearProcess（划卡清理入口，Bundle 含 packageName）
 *
 * 注意：OKillerBinder 实现的是 IAthenaKillerManager$Stub 而非 IAthenaService$Stub，
 * 所以 IAthenaService 的 Binder 入口拦截仅捕获从 com.oplus.athena 进程内发出的
 * 内部 kill 调用。真正的杀逻辑在 system_server 端由 RemoteService 执行，
 * 已在 AthenaKillHooks 中以方法级别 hook 覆盖。
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
                            // 实际 Binder code（从 Smali 确认，比 AIDL 声明大 1）：
                            // clearProcess=0xe0(224), athenaKill3=0xca(202),
                            // athenaKill=0x65(101), athenaKill2=0x67(103)
                            // 101/103 由 AthenaKillHooks 在 system_server 端以方法级别 hook 拦截
                            when (code) {
                                224 -> handleClearProcess(chain)
                                202 -> handleAthenaKill3(chain)
                                else -> chain.proceed()
                            }
                        }
                    module.log(Log.INFO, tag, "IAthenaService.Stub.onTransact hooked. code=224(clearProcess) 202(athenaKill3)")
                    return
                }
            }

            module.log(Log.WARN, tag, "AthenaBinderHooks: all hooks failed to install")
        } catch (t: Throwable) {
            module.log(Log.ERROR, tag, "AthenaBinderHooks install failed: ${t.message}")
        }
    }

    /**
     * 处理 clearProcess (Binder code 224, 0xe0) 调用。
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
     * 处理 athenaKill3 (Binder code 202, 0xca) 调用。
     * 从 data Parcel 中还原 List<Bundle>，逐个检查包名。
     * 有任意白名单包名 → 全量拦截（因为 Binder 层面无法从 Parcel 中移除单个条目）。
     *
     * 权衡：全量拦截可能影响同一批中非白名单 app 的清理。
     * 但 athenaKill3 通常由内存压力或系统级清理触发，不是划卡入口；
     * 白名单 app 的优先级高于非白名单 app 的清理效率。
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
                val hasProtected = bundleList.any { item ->
                    val bundle = item as? Bundle ?: return@any false
                    val pkg = bundle.getString("packageName")
                        ?: bundle.getString("pkg")
                        ?: return@any false
                    pkg in effectiveSet
                }
                if (hasProtected) {
                    module.log(
                        Log.INFO, tag,
                        "Blocked athenaKill3: contains protected apps"
                    )
                    val reply = chain.getArg(2) as? Parcel
                    reply?.writeNoException()
                    reply?.writeInt(0)
                    return true
                }
            }
        } catch (_: Throwable) {
            // Parcel 解析异常 → 放行，避免误杀正常调用
        }
        return chain.proceed()
    }
}

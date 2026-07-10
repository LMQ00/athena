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
 * 同时 Hook [IAthenaService.Stub.onTransact] 和
 * [IAthenaKillerManager.Stub.onTransact]，在 Binder 入口处拦截划卡杀进程。
 * 相比 SwipeKillHooks 在 kill 执行器层面拦截，此 Hook 在 Binder 请求分发阶段
 * 就终止调用链，更早也更可靠。
 *
 * ### 安装位置
 *
 * 此 Hook 安装在 **两个进程** 中：
 * - **system_server**（通过 [ModuleMain.onSystemServerStarting]）：
 *   拦截 SystemUI/Launcher 直接发往 system_server 的 Binder 调用。
 *   `IAthenaService` 是 ColorOS 系统服务（运行在 system_server），
 *   `IAthenaKillerManager` 也是 system_server 中的另一条 Binder 杀路径。
 * - **com.oplus.athena**（通过 [ModuleMain.onPackageReady]）：
 *   拦截 Athena 进程内的内部 kill 调用。
 *
 * ### Binder Code 说明
 *
 * 逆向 Athena 6.0.1 确认的 Binder transact code（IAthenaService AIDL + 编译确认）：
 *
 * AIDL 源文件（从 APK 中提取）声明 codes 为 athenaKill=100, athenaFreeze=101,
 * athenaKill2=102, athenaKill3=201, clearProcess=223。但 ColorOS AIDL 编译器
 * 在生成 Stub 时对显式赋值的 code 加上了 IBinder.FIRST_CALL_TRANSACTION(=1)
 * 的偏移，因此实际运行时使用的 code 比 AIDL 声明值大 1。
 *
 * 实际 IAthenaService Binder code（从 Smali .field 声明确认）：
 * - 101 (0x65) = athenaKill（旧版单包杀，已废弃）
 * - 102 (0x66) = athenaFreeze（冻结）
 * - 103 (0x67) = athenaKill2（新版单包杀，6 参数）
 * - 202 (0xca) = athenaKill3（新版批量 kill，List<Bundle>）
 * - 224 (0xe0) = clearProcess（划卡清理入口，Bundle 含 packageName）
 *
 * 注意：OKillerBinder 实现的是 IAthenaKillerManager$Stub 而非 IAthenaService$Stub，
 * 因此两个 Stub 都需要 hook。
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
        var hookedCount = 0
        val txnCodes = setOf(224, 202) // clearProcess / athenaKill3

        // ── Binder 1: IAthenaService$Stub ──────────────────────────────
        hookedCount += tryInstallStub(
            className = "com.oplus.app.IAthenaService\$Stub",
            stubLabel = "IAthenaService",
            txnCodes = txnCodes
        )

        // ── Binder 2: IAthenaKillerManager$Stub (OKillerBinder) ────────
        // OKillerBinder 实现的是 IAthenaKillerManager$Stub，
        // 这是另一条 Binder 杀路径，独立于 IAthenaService。
        hookedCount += tryInstallStub(
            className = "com.oplus.athena.interaction.IAthenaKillerManager\$Stub",
            stubLabel = "IAthenaKillerManager",
            txnCodes = txnCodes
        )

        if (hookedCount > 0) {
            module.log(
                Log.INFO, tag,
                "Install complete: $hookedCount Binder stub(s) hooked."
            )
        } else {
            module.log(
                Log.WARN, tag,
                "No Binder stub classes found — all onTransact hooks failed."
            )
        }
    }

    /**
     * 尝试为给定的 Binder Stub 类安装 onTransact Hook。
     *
     * @param className Binder Stub 全限定名（如 com.oplus.app.IAthenaService$Stub）
     * @param stubLabel 日志标签（如 IAthenaService）
     * @param txnCodes 需要拦截的 transact code 集合
     * @return 1 表示安装成功，0 表示失败（类/方法不存在）
     */
    private fun tryInstallStub(
        className: String,
        stubLabel: String,
        txnCodes: Set<Int>,
    ): Int {
        try {
            val stubClass = Class.forName(className, false, classLoader)
            val onTransact = stubClass.getDeclaredMethod(
                "onTransact",
                Int::class.javaPrimitiveType,
                Parcel::class.java,
                Parcel::class.java,
                Int::class.javaPrimitiveType
            )

            module.hook(onTransact)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    if (!enabled) return@intercept chain.proceed()
                    val code = chain.getArg(0) as? Int ?: return@intercept chain.proceed()
                    when (code) {
                        224 -> handleClearProcess(chain)    // clearProcess
                        202 -> handleAthenaKill3(chain)      // athenaKill3
                        101, 103 -> {
                            // athenaKill(101) / athenaKill2(103) 由
                            // AthenaKillHooks 在 system_server 端方法级别拦截
                            chain.proceed()
                        }
                        else -> chain.proceed()
                    }
                }

            module.log(
                Log.INFO, tag,
                "$stubLabel.onTransact hooked. codes=$txnCodes"
            )
            return 1
        } catch (_: ClassNotFoundException) {
            module.log(Log.DEBUG, tag, "$stubLabel class not found (expected in some builds)")
        } catch (_: NoSuchMethodException) {
            module.log(Log.WARN, tag, "$stubLabel.onTransact method not found")
        } catch (t: Throwable) {
            module.log(Log.ERROR, tag, "$stubLabel install failed: ${t.message}")
        }
        return 0
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

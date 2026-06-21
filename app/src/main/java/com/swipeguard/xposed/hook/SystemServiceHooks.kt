package com.swipeguard.xposed.hook

import android.util.Log
import com.swipeguard.xposed.data.RemoteConfigRepository
import com.swipeguard.xposed.model.SwipeGuardConfig
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * 系统服务辅助 Hook。
 *
 * 当前仅包含 [OomAdjuster.applyOomAdjLocked] 的拦截，
 * 将白名单进程的 oom_score_adj 强制设为 -17（系统服务级别），
 * 防止 LMK 在内存不足时杀死受保护的应用。
 *
 * 注意：这是辅助路径，划卡保护主要靠 [SwipeKillHooks]。
 */
class SystemServiceHooks(private val module: XposedModule) {

    private var config: SwipeGuardConfig = SwipeGuardConfig.DEFAULT
    private val tag = "SwipeGuard/SystemService"

    @Volatile
    private var setOomAdjMethod: Method? = null

    fun syncConfig(repo: RemoteConfigRepository) {
        config = repo.load()
    }

    fun install() {
        try {
            val oomAdjusterClass = Class.forName("com.android.server.am.OomAdjuster")
            val methods = oomAdjusterClass.declaredMethods
            val targetMethod = methods.firstOrNull { m ->
                m.name == "applyOomAdjLocked"
            } ?: run {
                module.log(Log.WARN, tag, "applyOomAdjLocked not found")
                return
            }

            module.hook(targetMethod)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    if (!config.enabled) return@intercept result

                    try {
                        // 尝试从 ProcessRecord 参数中提取包名
                        val procRecord = chain.args.firstOrNull { 
                            it?.javaClass?.name == "com.android.server.am.ProcessRecord" 
                        }
                        if (procRecord != null) {
                            val pkgField = procRecord.javaClass.getDeclaredField("packageName")
                            pkgField.isAccessible = true
                            val pkg = pkgField.get(procRecord) as? String
                            if (pkg != null && pkg in config.protectedApps) {
                                val pidField = procRecord.javaClass.getDeclaredField("pid")
                                pidField.isAccessible = true
                                val pid = pidField.getInt(procRecord)
                                setOomAdj(pid, -17)
                            }
                        }
                    } catch (_: Throwable) {
                        // 静默忽略，不干扰主流程
                    }
                    result
                }

            module.log(Log.INFO, tag, "SystemService hook installed")
        } catch (t: Throwable) {
            module.log(Log.ERROR, tag, "Failed to install SystemService hook: ${t.message}")
        }
    }

    private fun setOomAdj(pid: Int, adj: Int) {
        try {
            val m = setOomAdjMethod ?: run {
                val method = android.os.Process::class.java.getDeclaredMethod("setOomAdj", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                method.isAccessible = true
                setOomAdjMethod = method
                method
            }
            m.invoke(null, pid, adj)
        } catch (_: Throwable) {
            // 静默失败
        }
    }
}

package com.swipeguard.xposed.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * SwipeGuard 配置根容器。
 *
 * 双层白名单设计：
 * - **系统默认白名单**（[systemDefaults]）：预置的 ColorOS 系统默认白名单应用，
 *   基于对 `com.oplus.athena` v6.0.1 的逆向分析结果。
 * - **用户修改**：
 *   - [userAdditions]：用户额外添加的包名
 *   - [userRemovals]：用户从系统默认白名单中移除的包名
 *
 * 有效白名单 = (systemDefaults - userRemovals) + userAdditions
 *
 * 整个配置就是一个 JSON 对象，通过 SharedPreferences 跨进程共享。
 */
@Serializable
data class SwipeGuardConfig(
    /** 模块总开关 */
    var enabled: Boolean = true,

    /** 用户额外添加的白名单包名 */
    var userAdditions: Set<String> = emptySet(),

    /** 用户从系统默认白名单中移除的包名 */
    var userRemovals: Set<String> = emptySet(),

    /** 系统级默认白名单（逆向 Athena APK 提取 + 预置常用应用） */
    var systemDefaults: Set<String> = emptySet(),

    /** 标记 systemDefaults 是否已被初始化；false=首次加载时自动填充预置值 */
    var systemDefaultsInitialized: Boolean = false,

    /**
     * 写入 <whitePkg category="..."/> 的 category 编码。
     *
     * 三位独立编码（每位表示一个维度）：
     * - `100` = forcewhite（系统级强制白名单，不被杀）
     * - `010` = oppo/oneplus 自有应用
     * - `001` = 第三方应用
     */
    var whitelistCategory: String = "100",

    /** schema 版本，用于向前兼容 */
    val schemaVersion: Int = 2
) {
    /** 有效白名单 = 系统默认 - 用户移除 + 用户添加 */
    val effectiveProtectedApps: Set<String>
        get() = (systemDefaults - userRemovals) + userAdditions

    companion object {
        /**
         * ColorOS 16 Athena 系统级默认白名单。
         *
         * 通过 su 读取实际设备 `/data/oplus/os/bpm/sys_elsa_config_list.xml` 提取。
         * category=100=forcewhite（系统强制不杀）| 010=oplus自有 | 001=第三方
         */
        val KNOWN_SYSTEM_DEFAULTS: Set<String> = setOf(
            // === category=100 (forcewhite: 系统强制不杀) ===
            "com.coloros.soundrecorder",       // 录音机
            "com.hiby.music",                  // 海贝音乐
            "com.kugou.viper",                 // 酷狗蝰蛇音效
            "com.kiloo.subwaysurf",            // 地铁跑酷
            "com.oplus.cameradetection",        // 摄像头检测
            "com.coloros.alarmclock",           // 闹钟
            "com.google.android.apps.tycho",    // Google Tycho
            "com.tmobile.pr.mytmobile",         // T-Mobile
            "com.vzw.hss.myverizon",            // Verizon
            "com.unionpay.tsmservice",          // 银联 TSMPay
            "com.color.otaassistant",           // 系统 OTA 助手
            "com.oplus.claw",                   // OPlus Claw

            // === category=010 (OPlus 自有应用) ===
            "com.coloros.screenrecorder",      // 屏幕录制
            "com.oplus.melody",                // 铃声
            "com.heytap.uwbconnect",            // UWB 连接
            "com.oplus.onet",                  // OPlus Cloud
            "com.oplus.screenrecorder",         // 屏幕录制
            "com.coloros.oppopods",             // OPPO 耳机
            "com.oplus.caseflash",              // 手机壳闪光
            "com.edith.os",                    // Edith OS
            "com.oplus.riderMode",              // 骑行模式
            "com.realme.link",                 // Realme Link
            "com.realme.linkcn",               // Realme Link CN
            "com.oneplus.ctsprepare",           // 一加 CTS

            // === category=001 (第三方) ===
            "com.kuaidi.daijia.driver",         // 快嘀代驾司机
            "com.sdu.didi.gsui",                // 滴滴出行
            "com.ubercab.driver",               // Uber Driver
            "cn.edaijia.android.driverclient",  // e代驾
            "com.funcity.taxi.driver",          // 飞的
            "com.newgame.padtool",              // 新手游
            "com.sdu.didi.gui",                 // 滴滴车主
            "com.sankuai.meituan.dispatch.homebrew",    // 美团配送
            "com.sankuai.meituan.meituanwaimaibusiness", // 美团外卖商家
            "me.ele.napos",                    // 饿了么商家
            "com.samsung.android.app.watchmanager",     // Galaxy Watch
            "com.cxyw.suyun.ui",               // 货拉拉
            "com.google.android.marvin.talkback",       // TalkBack
            "com.android.email",               // 邮件
            "com.hp.android.printservice",      // HP 打印服务
            "com.hp.printercontrol",            // HP 打印控制
            "org.mopria.printplugin",           // Mopria 打印
            "com.xunmeng.merchant",             // 拼多多商家
            "com.lalamove.huolala.driver",      // 货拉拉司机
            "com.sankuai.meituan.merchant",     // 美团商家
            "com.google.android.talk",          // Google Talk
            "com.sankuai.meituan.dispatch.crowdsource",  // 美团众包
            "com.microsoft.windowsintune.companyportal", // Intune
            "com.microsoft.teams",              // Microsoft Teams
            "com.xiwei.logistics",              // 犀维物流
            "me.ele.crowdsource",               // 蜂鸟众包
            "cn.caocaokeji.dcdriver",           // 曹操司机
            "com.huaxiaozhu.driver",            // 华夏出行
            "com.wlqq",                        // 微粒贷
        )

        /** 空配置默认值（含预置系统白名单） */
        val DEFAULT = SwipeGuardConfig(systemDefaults = KNOWN_SYSTEM_DEFAULTS)

        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = false
            encodeDefaults = false
        }

        /**
         * 从 JSON 字符串反序列化配置。
         *
         * 自动处理 schema v1 → v2 迁移：
         * 旧版使用 `protectedApps` 字段 → 新版 `userAdditions` + `userRemovals`。
         */
        fun fromJson(s: String): SwipeGuardConfig =
            runCatching {
                val migrated = migrateFromV1(s)
                val cfg = json.decodeFromString(serializer(), migrated)
                // 首次使用且未初始化时填充预置默认值（之后用户可自由清空）
                if (!cfg.systemDefaultsInitialized && cfg.systemDefaults.isEmpty()) {
                    cfg.copy(
                        systemDefaults = KNOWN_SYSTEM_DEFAULTS,
                        systemDefaultsInitialized = true
                    )
                } else {
                    cfg
                }
            }.getOrDefault(DEFAULT)

        fun toJson(config: SwipeGuardConfig): String =
            json.encodeToString(serializer(), config)

        /**
         * schema v1 → v2 迁移：
         * 将旧 `protectedApps` 字段重命名为 `userAdditions`，
         * 并将 `schemaVersion` 设为 2。
         */
        private fun migrateFromV1(jsonStr: String): String {
            if (!jsonStr.contains("\"protectedApps\"")) return jsonStr
            return try {
                val element = json.parseToJsonElement(jsonStr)
                if (element !is JsonObject) return jsonStr
                val obj = element.jsonObject.toMutableMap()
                if (obj.containsKey("protectedApps") && !obj.containsKey("userAdditions")) {
                    obj["userAdditions"] = obj["protectedApps"]!!
                    obj.remove("protectedApps")
                    obj["schemaVersion"] = JsonPrimitive(2)
                    json.encodeToString(JsonElement.serializer(), JsonObject(obj))
                } else {
                    jsonStr
                }
            } catch (_: Throwable) {
                jsonStr
            }
        }
    }
}

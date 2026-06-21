package com.swipeguard.xposed.hook

import com.swipeguard.xposed.model.SwipeGuardConfig

/**
 * 将 SwipeGuard 配置转换为 ColorOS `sys_elsa_config_list.xml` 格式。
 *
 * ColorOS 16 的 OFreezer 白名单使用以下格式：
 * ```
 * <?xml version="1.0" encoding="utf-8"?>
 * <filter-conf>
 *   <whitePkg name="com.example.app" category="001"/>
 *   <whitePkg name="com.another.app" category="001"/>
 * </filter-conf>
 * ```
 *
 * category="001" 表示「用户级白名单」，最高优先级。
 */
object XmlPolicyBuilder {

    /**
     * 构建增强版的 sys_elsa_config_list.xml 内容。
     * 在保留原始 XML 结构的基础上，注入自定义 whitePkg 条目。
     */
    fun buildEnhancedXml(originalXml: String, config: SwipeGuardConfig): String {
        if (config.protectedApps.isEmpty() || !config.enabled) return originalXml

        val whitePkgEntries = config.protectedApps.joinToString("\n    ") { pkg ->
            """<whitePkg name="$pkg" category="001"/>"""
        }

        // 尝试在 </filter-conf> 前注入；如果没有，追加完整结构
        val closeTag = "</filter-conf>"
        val injectionPoint = originalXml.lastIndexOf(closeTag)
        
        return if (injectionPoint >= 0) {
            originalXml.substring(0, injectionPoint) +
                "\n    " + whitePkgEntries + "\n" +
                originalXml.substring(injectionPoint)
        } else {
            // 原始 XML 意外不包含 </filter-conf>，追加完整结构
            """<?xml version="1.0" encoding="utf-8"?>
<filter-conf>
    $whitePkgEntries
</filter-conf>"""
        }
    }

    /**
     * 构建增强版自启动白名单。
     * （可选功能：如果用户想在自启动白名单也添加条目）
     */
    fun buildEnhancedAutoStartWhiteList(originalContent: String, config: SwipeGuardConfig): String {
        if (config.protectedApps.isEmpty() || !config.enabled) return originalContent
        
        val lines = originalContent.lines().toMutableList()
        for (pkg in config.protectedApps) {
            if (lines.none { it.trim() == pkg }) {
                lines.add(pkg)
            }
        }
        return lines.joinToString("\n")
    }
}

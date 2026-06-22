package com.swipeguard.xposed.hook

import android.util.Log
import com.swipeguard.xposed.model.SwipeGuardConfig
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * 将 SwipeGuard 配置转换为 ColorOS `sys_elsa_config_list.xml` 格式。
 *
 * ColorOS 16 的 OFreezer 白名单使用以下格式：
 * ```xml
 * <whitePkg name="com.example.app" category="001"/>
 * <whitePkg name="com.another.app" category="001"/>
 * ```
 *
 * category 编码（三位独立）：
 * - `100` = forcewhite（系统强制白名单，不被杀）
 * - `010` = oppo/oneplus 自有应用
 * - `001` = 第三方应用
 *
 * ### 注入策略（t5 优化）
 *
 * 1. **优先使用真 XML 解析** (`javax.xml.parsers.DocumentBuilderFactory`)：
 *    解析后追加 `<whitePkg>` 元素，再序列化为字符串。避免字符串切片
 *    丢失系统原有条目。
 * 2. **fallback 字符串方案**：`javax.xml` 在 system_server 早期不可用时，
 *    回退到正则提取现有包名 + 在 `</filter-conf>` 前注入。
 * 3. **白名单去重**：追加前检查是否已存在同包名 `<whitePkg>`，避免
 *    每次热更新都追加导致 XML 越来越臃肿。
 */
object XmlPolicyBuilder {

    private const val TAG = "SwipeGuard/XmlPolicy"

    // ---- regex for fallback extraction ---------------------------------
    private val WHITE_PKG_PATTERN = """<whitePkg\s+name\s*=\s*"([^"]*)"\s*""".toRegex()

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * 构建增强版的 sys_elsa_config_list.xml 内容。
     * 优先使用真 XML 解析器；解析失败时 fallback 到字符串方案。
     *
     * @param originalXml 系统原始 XML 内容
     * @param config      当前 SwipeGuard 配置
     * @return 增强后的 XML 字符串（含新注入的 whitePkg 条目）
     */
    fun buildEnhancedXml(originalXml: String, config: SwipeGuardConfig): String {
        if (config.protectedApps.isEmpty() || !config.enabled) return originalXml

        return try {
            buildEnhancedXmlViaParser(originalXml, config)
        } catch (t: Throwable) {
            Log.w(TAG, "XML parser failed, fallback to string injection", t)
            buildEnhancedXmlFallback(originalXml, config)
        }
    }

    /**
     * 构建增强版自启动白名单。
     * 在保留原始内容的基础上插入未重复的包名行。
     */
    fun buildEnhancedAutoStartWhiteList(
        originalContent: String,
        config: SwipeGuardConfig,
    ): String {
        if (config.protectedApps.isEmpty() || !config.enabled) return originalContent

        val lines = originalContent.lines().toMutableList()
        for (pkg in config.protectedApps.sorted()) {
            if (lines.none { it.trim() == pkg }) {
                lines.add(pkg)
            }
        }
        return lines.joinToString("\n")
    }

    // ------------------------------------------------------------------
    // 真 XML 解析路径
    // ------------------------------------------------------------------

    /**
     * 使用 [DocumentBuilderFactory] 解析原始 XML，追加白名单条目，再序列化。
     *
     * 优势：
     * - 以 DOM 树的方式操作，不会丢失系统原有 `<whitePkg>` 与其属性
     * - 天然去重：遍历已有 `name` 属性后跳过重复
     *
     * @throws Exception 解析/转换过程中的任何异常（由调用方统一 fallback）
     */
    private fun buildEnhancedXmlViaParser(
        originalXml: String,
        config: SwipeGuardConfig,
    ): String {
        val dbFactory = DocumentBuilderFactory.newInstance()
        val dBuilder = dbFactory.newDocumentBuilder()
        val doc = dBuilder.parse(ByteArrayInputStream(originalXml.toByteArray(Charsets.UTF_8)))
        doc.documentElement.normalize()

        val root = doc.documentElement

        // 收集已有 whitePkg 包名
        val existingPkgs = mutableSetOf<String>()
        val whitePkgNodes = root.getElementsByTagName("whitePkg")
        for (i in 0 until whitePkgNodes.length) {
            val element = whitePkgNodes.item(i) as? Element
            val name = element?.getAttribute("name")
            if (name != null) existingPkgs.add(name)
        }

        // 追加新条目（去重后）
        val category = config.whitelistCategory
        var appended = 0
        for (pkg in config.protectedApps.sorted()) {
            if (pkg in existingPkgs) continue
            val elem = doc.createElement("whitePkg")
            elem.setAttribute("name", pkg)
            elem.setAttribute("category", category)
            root.appendChild(doc.createTextNode("\n    "))
            root.appendChild(elem)
            appended++
        }

        if (appended == 0) return originalXml // 无变更，直接返回原始内容

        // 确保末尾换行后跟 </filter-conf>
        root.appendChild(doc.createTextNode("\n"))

        // 序列化回字符串
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4")
        val writer = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(writer))

        // 如果原始 XML 含 declaration，在序列化结果前补上
        val serialized = writer.toString()
        return if (originalXml.trimStart().startsWith("<?xml")) {
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n$serialized"
        } else {
            serialized
        }
    }

    // ------------------------------------------------------------------
    // 字符串 fallback 路径
    // ------------------------------------------------------------------

    /**
     * 字符串方案 — 在 `</filter-conf>` 前注入新的 `<whitePkg>`。
     *
     * 保留原始 XML 结构不变，仅在最末尾追加新条目。通过正则提取已有
     * 包名实现去重。
     */
    private fun buildEnhancedXmlFallback(
        originalXml: String,
        config: SwipeGuardConfig,
    ): String {
        val closeTag = "</filter-conf>"
        val injectionPoint = originalXml.lastIndexOf(closeTag)
        if (injectionPoint < 0) {
            // 意外异常格式，从零构建
            return buildFromScratch(config)
        }

        val existingPkgs = extractExistingWhitePkgs(originalXml)
        val category = config.whitelistCategory

        val newEntries = config.protectedApps
            .filter { it !in existingPkgs }
            .sorted()
            .joinToString("\n    ") { pkg ->
                """<whitePkg name="$pkg" category="$category"/>"""
            }

        if (newEntries.isEmpty()) return originalXml

        return originalXml.substring(0, injectionPoint) +
            "\n    " + newEntries + "\n" +
            originalXml.substring(injectionPoint)
    }

    /**
     * 通过正则提取所有 `<whitePkg name="..."` 中的包名。
     * 注意：不验证 XML 合法性，仅在 fallback 路径使用。
     */
    private fun extractExistingWhitePkgs(xml: String): Set<String> {
        val pkgs = mutableSetOf<String>()
        for (match in WHITE_PKG_PATTERN.findAll(xml)) {
            match.groupValues.getOrNull(1)?.let { pkgs.add(it) }
        }
        return pkgs
    }

    /** 当原始 XML 完全不包含预期结构时的兜底。 */
    private fun buildFromScratch(config: SwipeGuardConfig): String {
        val category = config.whitelistCategory
        val entries = config.protectedApps.sorted().joinToString("\n    ") { pkg ->
            """<whitePkg name="$pkg" category="$category"/>"""
        }
        return """<?xml version="1.0" encoding="utf-8"?>
<filter-conf>
    $entries
</filter-conf>"""
    }
}

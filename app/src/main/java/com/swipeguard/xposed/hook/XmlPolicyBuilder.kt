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
 *    解析后移除 [SwipeGuardConfig.userRemovals] 中的条目，新增
 *    [SwipeGuardConfig.userAdditions] 中的条目，再序列化为字符串。
 *    避免字符串切片丢失系统原有条目。
 * 2. **fallback 字符串方案**：`javax.xml` 在 system_server 早期不可用时，
 *    回退到正则提取现有包名 + 过滤/注入。
 * 3. **白名单去重**：追加前检查是否已存在同包名 `<whitePkg>`，避免
 *    每次热更新都追加导致 XML 越来越臃肿。
 */
object XmlPolicyBuilder {

    private const val TAG = "SwipeGuard/XmlPolicy"

    // ---- regex for fallback extraction ---------------------------------
    private val WHITE_PKG_PATTERN = """<(?:[a-zA-Z_][\w.-]*:)?whitePkg\s+name\s*=\s*"([^"]*)"\s*""".toRegex()

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * 构建增强版的 sys_elsa_config_list.xml 内容。
     *
     * 从原始 XML 中移除 [SwipeGuardConfig.userRemovals] 中的条目，
     * 并添加 [SwipeGuardConfig.userAdditions] 中的条目。
     * 优先使用真 XML 解析器；解析失败时 fallback 到字符串方案。
     *
     * @param originalXml 系统原始 XML 内容
     * @param config      当前 SwipeGuard 配置
     * @return 增强后的 XML 字符串（含删除/新增后的 whitePkg 条目）
     */
    fun buildEnhancedXml(originalXml: String, config: SwipeGuardConfig): String {
        if (!config.enabled) return originalXml
        if (config.userAdditions.isEmpty() && config.userRemovals.isEmpty()) return originalXml

        return try {
            buildEnhancedXmlViaParser(originalXml, config)
        } catch (t: Throwable) {
            Log.w(TAG, "XML parser failed, fallback to string injection", t)
            buildEnhancedXmlFallback(originalXml, config)
        }
    }

    /**
     * 构建增强版自启动白名单。
     *
     * 从原始文本内容中移除 [SwipeGuardConfig.userRemovals] 中的包名行，
     * 并追加 [SwipeGuardConfig.userAdditions] 中未重复的包名行。
     */
    fun buildEnhancedAutoStartWhiteList(
        originalContent: String,
        config: SwipeGuardConfig,
    ): String {
        if (!config.enabled) return originalContent
        if (config.userAdditions.isEmpty() && config.userRemovals.isEmpty()) return originalContent

        // 1. 过滤掉 userRemovals 中的包名
        val lines = originalContent.lines().filter { line ->
            val trimmed = line.trim()
            trimmed.isBlank() || trimmed !in config.userRemovals
        }.toMutableList()

        // 2. 添加 userAdditions 中未重复的包名
        for (pkg in config.userAdditions.sorted()) {
            if (lines.none { it.trim() == pkg }) {
                lines.add(pkg)
            }
        }
        return lines.joinToString("\n")
    }

    /**
     * 从 ColorOS `sys_elsa_config_list.xml` 内容中提取所有 `<whitePkg>` 的
     * `name` 属性值 —— 即系统预置的白名单包名。
     *
     * 使用正则提取，兼容命名空间前缀，不验证 XML 合法性。
     *
     * @param xml 原始 XML 字符串
     * @return 系统预置白名单包名集合
     */
    fun extractWhitePkgNames(xml: String): Set<String> {
        return extractExistingWhitePkgs(xml)
    }

    // ------------------------------------------------------------------
    // 真 XML 解析路径
    // ------------------------------------------------------------------

    /**
     * 使用 [DocumentBuilderFactory] 解析原始 XML，移除 [SwipeGuardConfig.userRemovals]
     * 中的 `<whitePkg>` 元素，追加 [SwipeGuardConfig.userAdditions] 中的条目，再序列化。
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
        dbFactory.setNamespaceAware(true)
        val dBuilder = dbFactory.newDocumentBuilder()
        val doc = dBuilder.parse(ByteArrayInputStream(originalXml.toByteArray(Charsets.UTF_8)))
        doc.documentElement.normalize()

        val root = doc.documentElement

        // 1. 收集已有 whitePkg 并移除 userRemovals 中的条目
        val existingPkgs = mutableSetOf<String>()
        val nodesToRemove = mutableListOf<Element>()
        val whitePkgNodes = root.getElementsByTagNameNS("*", "whitePkg")
        for (i in 0 until whitePkgNodes.length) {
            val element = whitePkgNodes.item(i) as? Element ?: continue
            val name = element.getAttribute("name")
            if (name.isNullOrEmpty()) continue
            existingPkgs.add(name)
            if (name in config.userRemovals) {
                nodesToRemove.add(element)
            }
        }
        for (node in nodesToRemove) {
            node.parentNode?.removeChild(node)
        }

        // 2. 追加 userAdditions 中的新条目（去重后）
        val category = config.whitelistCategory
        var appended = 0
        for (pkg in config.userAdditions.sorted()) {
            if (pkg in existingPkgs) continue
            val elem = doc.createElement("whitePkg")
            elem.setAttribute("name", pkg)
            elem.setAttribute("category", category)
            root.appendChild(doc.createTextNode("\n    "))
            root.appendChild(elem)
            appended++
        }

        if (nodesToRemove.isEmpty() && appended == 0) return originalXml

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
     * 字符串方案 — 移除 [SwipeGuardConfig.userRemovals] 中的条目，
     * 并在 `</filter-conf>` 前注入 [SwipeGuardConfig.userAdditions]。
     *
     * 保留原始 XML 结构不变，通过正则过滤已有条目。
     */
    private fun buildEnhancedXmlFallback(
        originalXml: String,
        config: SwipeGuardConfig,
    ): String {
        // 1. 提取已有 whitePkg 并过滤 userRemovals
        val existingPkgs = extractExistingWhitePkgs(originalXml)
        val keepPkgs = existingPkgs - config.userRemovals

        // 2. 计算需新增的条目（去重）
        val toAdd = config.userAdditions.filter { it !in keepPkgs }.sorted()

        // 3. 构建新条目字符串
        val category = config.whitelistCategory
        val newEntries = toAdd.joinToString("\n    ") { pkg ->
            """<whitePkg name="$pkg" category="$category"/>"""
        }

        // 4. 移除 userRemovals 中的条目（正则替换）
        var xml = originalXml
        for (pkg in config.userRemovals) {
            // 替换 <whitePkg name="pkg" ... /> 为空（包括前后空白）
            xml = xml.replace(Regex("""\s*<whitePkg[^>]*\s+name\s*=\s*"${Regex.escape(pkg)}"[^>]*/\s*>"""), "")
        }

        // 5. 在 </filter-conf> 前注入新条目
        if (newEntries.isNotEmpty()) {
            val closeTag = "</filter-conf>"
            val injectionPoint = xml.lastIndexOf(closeTag)
            if (injectionPoint >= 0) {
                xml = xml.substring(0, injectionPoint) +
                    "\n    " + newEntries + "\n" +
                    xml.substring(injectionPoint)
            } else {
                // 意外异常格式，从零构建
                xml = buildFromScratch(config)
            }
        }

        return xml
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
        val entries = config.userAdditions.sorted().joinToString("\n    ") { pkg ->
            """<whitePkg name="$pkg" category="$category"/>"""
        }
        return """<?xml version="1.0" encoding="utf-8"?>
<filter-conf>
    $entries
</filter-conf>"""
    }
}

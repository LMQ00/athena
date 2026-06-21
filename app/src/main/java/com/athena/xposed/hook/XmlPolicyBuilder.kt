package com.athena.xposed.hook

import android.util.Log
import com.athena.xposed.model.FreezePolicy
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.stream.StreamResult

/**
 * 将 [FreezePolicy] 转换为 ColorOS `sys_elsa_config_list.xml` / 自启动白名单
 * 文本格式的工具类。
 *
 * 该对象无状态、线程安全，可被 [OplusConfigHooks] 在 Hook 闭包内自由调用。
 *
 * 设计取舍：
 *  - 优先用 DOM 解析原始 XML，注入条目后重新序列化，保证输出良构。
 *  - DOM 解析失败（原始 XML 与预期 schema 差异较大或非良构）时回退到
 *    **字符串注入**：在根元素闭合标签前追加片段。此路径不保证语义严格
 *    正确，但能保证模块白名单条目出现在文件中，供 ColorOS 解析器尽力识别。
 *  - 注入条目均做去重（按 `name` 属性），避免重复 hook 调用累积重复节点。
 *
 * 关于 ColorOS `sys_elsa_config_list.xml` schema：
 *  调研样本形如：
 *  ```
 *  <elsa_config>
 *    <white_pkg_list>
 *      <pkg_item name="com.foo" />
 *    </white_pkg_list>
 *    <ff_pkg_list>
 *      <pkg_item name="com.bar" />
 *    </ff_pkg_list>
 *    <im_pkg_list>
 *      <pkg_item name="com.tencent.mm" />
 *    </im_pkg_list>
 *  </elsa_config>
 *  ```
 *  本工具兼容上述结构；若实际 schema 不同，DOM 路径会创建缺失的容器，
 *  字符串路径会在根闭合标签前追加等价片段，二者均尽力保持可解析。
 */
object XmlPolicyBuilder {

    private const val TAG = "Athena/XmlPolicyBuilder"

    // ---- 容器 / 条目元素名（与 ColorOS 采样一致） ---------------------
    private const val TAG_WHITE_LIST = "white_pkg_list"
    private const val TAG_FF_LIST = "ff_pkg_list"
    private const val TAG_IM_LIST = "im_pkg_list"
    private const val TAG_PKG_ITEM = "pkg_item"
    private const val ATTR_NAME = "name"
    private const val ATTR_TIMEOUT = "timeout"
    private const val ATTR_HEARTBEAT = "heartbeat"

    /**
     * 将 [FreezePolicy] 转换为增强版的 `sys_elsa_config_list.xml` 字符串。
     *
     * @param originalXml 系统原始 XML 内容（由 FileInputStream 读取得到）
     * @param policy      模块当前合并后的有效冻结策略
     * @return 注入 whitePkg / ffPkg / imPkg 与超时覆盖后的修改版 XML；
     *         若注入无可行路径，返回 [originalXml]（即不改写，安全降级）
     */
    fun buildEnhancedXml(originalXml: String, policy: FreezePolicy): String {
        if (originalXml.isBlank()) {
            // 原文件为空：直接生成一份仅含模块策略的 XML
            return buildXmlFromScratch(policy)
        }

        // 优先 DOM 路径
        return try {
            buildEnhancedXmlDom(originalXml, policy)
        } catch (t: Throwable) {
            // DOM 解析 / 序列化失败：回退字符串注入，再失败则原样返回
            runCatching { buildEnhancedXmlString(originalXml, policy) }
                .getOrDefault(originalXml)
        }
    }

    /**
     * 构建增强的自启动白名单文本。
     *
     * ColorOS `startup/autostart_white_list.txt` 通常每行一个包名（亦可能以
     * 逗号 / 空白分隔）。本方法按行解析原始内容，去重后追加模块白名单包名，
     * 保持与原始一致的换行风格。
     *
     * @param originalContent 系统原始白名单内容
     * @param whitePkgList    模块要追加的白名单包名集合
     * @return 合并去重后的白名单文本
     */
    fun buildEnhancedAutoStartWhiteList(
        originalContent: String,
        whitePkgList: Set<String>,
    ): String {
        if (whitePkgList.isEmpty()) return originalContent

        // 解析原始白名单中已存在的包名（按非空白 token 切分，兼容多种分隔符）
        val existing: MutableSet<String> = LinkedHashSet()
        originalContent.split(Regex("\\s+|,|;"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { existing.add(it) }

        // 追加模块白名单（去重，保持插入顺序便于人工排查）
        val merged = LinkedHashSet<String>(existing.size + whitePkgList.size)
        merged.addAll(existing)
        merged.addAll(whitePkgList)

        // 以换行分隔输出，保持原文件「每行一个包名」的可读风格
        return merged.joinToString(separator = "\n", postfix = "\n")
    }

    // ------------------------------------------------------------------
    // DOM 路径
    // ------------------------------------------------------------------

    private fun buildEnhancedXmlDom(originalXml: String, policy: FreezePolicy): String {
        val factory = DocumentBuilderFactory.newInstance()
        // 关闭外部实体解析，防御 XXE（system_server 内风险有限，仍遵循最小信任）。
        // Android 的 XML 实现可能不支持部分 Xerces 特性名，逐项 try-catch，
        // 任一特性不可用不应阻止 DOM 解析本身。
        runCatching { factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "") }
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }.onFailure {
            Log.w(TAG, "Failed to set XXE feature: ${it.message}")
        }
        factory.isExpandEntityReferences = false
        val doc = factory.newDocumentBuilder().parse(originalXml.byteInputStream(Charsets.UTF_8))

        val root = doc.documentElement
            ?: throw IllegalStateException("No document element in elsa config XML.")

        // 注入 whitePkg
        if (policy.whitePkg.isNotEmpty()) {
            val container = ensureContainer(doc, root, TAG_WHITE_LIST)
            injectPkgItems(doc, container, policy.whitePkg)
        }

        // 注入 ffPkg（带超时属性）
        if (policy.ffPkg.isNotEmpty()) {
            val container = ensureContainer(doc, root, TAG_FF_LIST)
            injectPkgItems(
                doc, container, policy.ffPkg,
                extraAttrs = if (policy.ffTimeoutMs > 0L)
                    mapOf(ATTR_TIMEOUT to policy.ffTimeoutMs.toString())
                else emptyMap()
            )
        }

        // 注入 imPkg（带心跳超时属性）
        if (policy.imPkg.isNotEmpty()) {
            val container = ensureContainer(doc, root, TAG_IM_LIST)
            injectPkgItems(
                doc, container, policy.imPkg,
                extraAttrs = if (policy.imTimeoutMs > 0L)
                    mapOf(ATTR_HEARTBEAT to policy.imTimeoutMs.toString())
                else emptyMap()
            )
        }

        return serialize(doc)
    }

    /**
     * 查找或创建名为 [tag] 的子容器（挂在 [root] 下）。
     * 已存在则复用，避免产生重复容器节点。
     */
    private fun ensureContainer(doc: Document, root: Element, tag: String): Element {
        val existing = root.getElementsByTagName(tag)
        if (existing.length > 0) return existing.item(0) as Element
        val created = doc.createElement(tag)
        // 追加到末尾；保持顺序：white → ff → im
        root.appendChild(created)
        return created
    }

    /**
     * 向 [container] 中注入 [pkgNames] 对应的 `<pkg_item name="..."/>`，
     * 对已存在同名条目跳过（去重）。
     */
    private fun injectPkgItems(
        doc: Document,
        container: Element,
        pkgNames: Set<String>,
        extraAttrs: Map<String, String> = emptyMap(),
    ) {
        val existingNames: Set<String> = container.getElementsByTagName(TAG_PKG_ITEM)
            .let { nodes ->
                (0 until nodes.length).mapNotNull { i ->
                    (nodes.item(i) as? Element)?.getAttribute(ATTR_NAME)?.takeIf { it.isNotEmpty() }
                }.toSet()
            }

        for (pkg in pkgNames) {
            if (pkg in existingNames) continue
            val item = doc.createElement(TAG_PKG_ITEM)
            item.setAttribute(ATTR_NAME, pkg)
            for ((k, v) in extraAttrs) item.setAttribute(k, v)
            container.appendChild(item)
        }
    }

    /** 将 DOM 序列化为带 XML 声明的字符串。 */
    private fun serialize(doc: Document): String {
        val tf = TransformerFactory.newInstance()
        val t = tf.newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.INDENT, "yes")
            setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        }
        val sw = StringWriter()
        t.transform(javax.xml.transform.dom.DOMSource(doc), StreamResult(sw))
        return sw.toString()
    }

    // ------------------------------------------------------------------
    // 字符串回退路径
    // ------------------------------------------------------------------

    /**
     * DOM 不可用时的字符串注入：在根元素闭合标签前追加 whitePkg / ffPkg / imPkg
     * 片段。不要求严格 schema，仅保证模块条目出现于文档内。
     */
    private fun buildEnhancedXmlString(originalXml: String, policy: FreezePolicy): String {
        val sb = StringBuilder(originalXml)

        // 追加 whitePkg
        if (policy.whitePkg.isNotEmpty()) {
            sb.insertBeforeRootClose(
                buildListBlock(TAG_WHITE_LIST, policy.whitePkg, extraAttr = null)
            )
        }
        if (policy.ffPkg.isNotEmpty()) {
            val extra = if (policy.ffTimeoutMs > 0L)
                " $ATTR_TIMEOUT=\"${policy.ffTimeoutMs}\"" else ""
            sb.insertBeforeRootClose(
                buildListBlock(TAG_FF_LIST, policy.ffPkg, extraAttr = extra)
            )
        }
        if (policy.imPkg.isNotEmpty()) {
            val extra = if (policy.imTimeoutMs > 0L)
                " $ATTR_HEARTBEAT=\"${policy.imTimeoutMs}\"" else ""
            sb.insertBeforeRootClose(
                buildListBlock(TAG_IM_LIST, policy.imPkg, extraAttr = extra)
            )
        }
        return sb.toString()
    }

    /** 拼接一个 `<xxx_list><pkg_item name=".."/></xxx_list>` 片段。 */
    private fun buildListBlock(
        listTag: String,
        pkgs: Set<String>,
        extraAttr: String?,
    ): String {
        val items = pkgs.joinToString("\n    ") { pkg ->
            "  <$TAG_PKG_ITEM $ATTR_NAME=\"$pkg\"${extraAttr.orEmpty()} />"
        }
        return "  <$listTag>\n    $items\n  </$listTag>\n"
    }

    /**
     * 在根元素闭合标签 `</elsa_config>` 之前插入 [fragment]。
     * 使用具体的根标签名避免被注释或 CDATA 中的 `</` 误导。
     */
    private fun StringBuilder.insertBeforeRootClose(fragment: String) {
        val closeTag = "</elsa_config>"
        val closeIdx = this.lastIndexOf(closeTag)
        if (closeIdx < 0) {
            // 找不到预期的闭合标签：直接追加
            this.append(fragment)
        } else {
            this.insert(closeIdx, fragment)
        }
    }

    // ------------------------------------------------------------------
    // 从零生成
    // ------------------------------------------------------------------

    /** 原始 XML 为空时，仅依据模块策略生成一份完整的 elsa 配置。 */
    private fun buildXmlFromScratch(policy: FreezePolicy): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<elsa_config>\n")
        if (policy.whitePkg.isNotEmpty()) {
            sb.append(buildListBlock(TAG_WHITE_LIST, policy.whitePkg, extraAttr = null))
        }
        if (policy.ffPkg.isNotEmpty()) {
            val extra = if (policy.ffTimeoutMs > 0L)
                " $ATTR_TIMEOUT=\"${policy.ffTimeoutMs}\"" else ""
            sb.append(buildListBlock(TAG_FF_LIST, policy.ffPkg, extraAttr = extra))
        }
        if (policy.imPkg.isNotEmpty()) {
            val extra = if (policy.imTimeoutMs > 0L)
                " $ATTR_HEARTBEAT=\"${policy.imTimeoutMs}\"" else ""
            sb.append(buildListBlock(TAG_IM_LIST, policy.imPkg, extraAttr = extra))
        }
        sb.append("</elsa_config>\n")
        return sb.toString()
    }
}

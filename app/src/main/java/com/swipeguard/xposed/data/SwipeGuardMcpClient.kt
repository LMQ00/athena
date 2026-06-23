package com.swipeguard.xposed.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * MT APK MCP 客户端，用于从系统 Athena APK 读取默认白名单。
 *
 * MCP 端点：http://127.0.0.1:8787/mcp
 * 协议：JSON-RPC 2.0（Application Level）
 *
 * 注意：MCP 是 MT Manager 内置功能，不一定在所有设备上可用。
 * 调用前应检查 MCP 是否可达。
 */
object SwipeGuardMcpClient {

    private const val MCP_URL = "http://127.0.0.1:8787/mcp"
    private const val TAG = "SwipeGuard/MCP"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 从系统 Athena APK 的 sys_elsa_config_list.xml 中提取 whitePkg 包名。
     *
     * @return 系统默认白名单包名集合，MCP 不可达或解析失败时返回空集合
     */
    fun loadSystemDefaults(): Set<String> {
        return try {
            // 1. Initialize MCP session
            val wsId = initialize()
            if (wsId == null) {
                android.util.Log.w(TAG, "MCP initialize failed")
                return emptySet()
            }

            // 2. Open default APK (should be system Athena)
            if (!openDefaultApk()) {
                android.util.Log.w(TAG, "MCP open APK failed")
                return emptySet()
            }

            // 3. Read sys_elsa_config_list.xml from APK resources
            val xml = readElsaConfigXml()
            if (xml == null) {
                android.util.Log.w(TAG, "MCP read XML failed")
                return emptySet()
            }

            // 4. Extract whitePkg names
            val defaults = extractWhitePkgNames(xml)
            android.util.Log.i(TAG, "Loaded ${defaults.size} system defaults from MCP")
            defaults
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "MCP loadSystemDefaults failed", t)
            emptySet()
        }
    }

    // ------------------------------------------------------------------
    // MCP JSON-RPC calls
    // ------------------------------------------------------------------

    private fun initialize(): String? {
        val resp = mcpCall("initialize", buildJsonObject {
            put("protocolVersion", "2025-06-18")
            put("capabilities", buildJsonObject {})
        })
        return resp?.jsonObject?.get("result")?.jsonObject?.get("workspaceId")?.jsonPrimitive?.content
    }

    private fun openDefaultApk(): Boolean {
        val resp = mcpCall("tools/call", buildJsonObject {
            put("name", "mt_apk_open")
            put("arguments", buildJsonObject {})
        })
        val text = resp?.jsonObject?.get("result")?.jsonObject
            ?.get("content")?.jsonArray?.get(0)?.jsonObject
            ?.get("text")?.jsonPrimitive?.content ?: return false
        val data = json.parseToJsonElement(text).jsonObject
        return data.get("ok")?.jsonPrimitive?.boolean == true
    }

    private fun readElsaConfigXml(): String? {
        val resp = mcpCall("tools/call", buildJsonObject {
            put("name", "mt_apk_read_text")
            put("arguments", buildJsonObject {
                put("path", "res/xml/sys_elsa_config_list.xml")
            })
        })
        return resp?.jsonObject?.get("result")?.jsonObject
            ?.get("content")?.jsonArray?.get(0)?.jsonObject
            ?.get("text")?.jsonPrimitive?.content
    }

    // ------------------------------------------------------------------
    // XML parsing
    // ------------------------------------------------------------------

    /**
     * 从 ColorOS `sys_elsa_config_list.xml` 内容中提取所有 `<whitePkg>` 的
     * `name` 属性值。
     */
    private fun extractWhitePkgNames(xml: String): Set<String> {
        val pattern = """<whitePkg\s+name\s*=\s*"([^"]*)"\s*""".toRegex()
        return pattern.findAll(xml).map { it.groupValues[1] }.toSet()
    }

    // ------------------------------------------------------------------
    // HTTP JSON-RPC transport
    // ------------------------------------------------------------------

    /**
     * 发送 MCP JSON-RPC 请求并返回响应。
     * 使用短超时（3s connect + 5s read），MCP 不可达时快速失败。
     */
    private fun mcpCall(method: String, params: JsonElement?): JsonElement? {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", method)
            if (params != null) put("params", params)
        }
        val conn = URL(MCP_URL).openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            doOutput = true
            connectTimeout = 3000
            readTimeout = 5000
        }
        return try {
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val response = conn.inputStream.bufferedReader().readText()
            json.parseToJsonElement(response)
        } finally {
            conn.disconnect()
        }
    }
}

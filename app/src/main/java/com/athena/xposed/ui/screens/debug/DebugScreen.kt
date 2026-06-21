@file:OptIn(ExperimentalMaterial3Api::class)

package com.athena.xposed.ui.screens.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athena.xposed.model.AthenaConfig
import com.athena.xposed.model.FreezePolicy
import com.athena.xposed.ui.data.ConfigViewModel

/**
 * 调试页面。
 *
 * 顶部为调试开关（调试日志 / native 文件注入），中部为匹配统计卡片
 * （白名单 / 黑名单条目数量），底部为当前 [FreezePolicy] 的 JSON 预览
 * 与「复制到剪贴板」按钮。
 *
 * 统计数据均来自 [ConfigViewModel.state] 快照，不直接调用匹配引擎，
 * 避免在 UI 进程重复构建快照。
 */
@Composable
fun DebugScreen() {
    val config by ConfigViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val module = config.module

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "调试",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // ---- 调试开关 ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                ListItem(
                    headlineContent = { Text("调试日志") },
                    supportingContent = { Text("向 Logcat 输出详细日志（Tag: Athena）") },
                    trailingContent = {
                        Switch(
                            checked = module.debugLog,
                            onCheckedChange = ConfigViewModel::setDebugLog,
                        )
                    },
                )
                ListItem(
                    headlineContent = { Text("native 文件注入") },
                    supportingContent = { Text("直接修改原始 XML 文件（高级调试）") },
                    trailingContent = {
                        Switch(
                            checked = module.nativeFileInjection,
                            onCheckedChange = ConfigViewModel::setNativeFileInjection,
                        )
                    },
                )
            }
        }

        // ---- 匹配统计 ----
        StatsCard(config = config)

        // ---- FreezePolicy 预览 ----
        FreezePolicyPreviewCard(
            config = config,
            onCopy = { copyToClipboard(context, it) },
        )
    }
}

/**
 * 匹配统计卡片 —— 显示白名单 / 黑名单 / 自定义冻结配置条目数量。
 */
@Composable
private fun StatsCard(config: AthenaConfig) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "匹配统计",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Column(modifier = Modifier.padding(top = 8.dp)) {
                StatRow(label = "白名单条目数", value = config.whiteList.size.toString())
                StatRow(label = "黑名单条目数", value = config.blackList.size.toString())
                StatRow(label = "自定义冻结配置数", value = config.customEntries.size.toString())
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * FreezePolicy 预览卡片。
 *
 * 当前实现直接从 [AthenaConfig] 推导一份占位 [FreezePolicy]（仅用于 UI 预览），
 * 将白名单 / 黑名单包名集合与全局默认超时填入。完整「合并自定义冻结配置」
 * 逻辑由 [com.athena.xposed.engine.PolicyMatcher] 在 Hook 进程执行，UI 此处仅
 * 展示供调试参考。
 */
@Composable
private fun FreezePolicyPreviewCard(
    config: AthenaConfig,
    onCopy: (String) -> Unit,
) {
    val previewPolicy = remember(config) { buildPreviewPolicy(config) }
    val json = remember(previewPolicy) {
        AthenaConfig.Json.encodeToString(FreezePolicy.serializer(), previewPolicy)
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "FreezePolicy 预览",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "由当前配置推导的合并策略（仅供参考，实际生效以 Hook 进程为准）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Text(
                    text = json,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
            FilledTonalButton(
                onClick = { onCopy(json) },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null)
                Text(
                    text = "复制到剪贴板",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/**
 * 根据当前 [AthenaConfig] 构建 UI 预览用的 [FreezePolicy]。
 *
 * 注意：此处仅做包名集合的简单聚合，不实现 [FreezePolicy.merge] 的自定义
 * 配置叠加，避免在 UI 进程重复实现引擎逻辑。
 */
private fun buildPreviewPolicy(config: AthenaConfig): FreezePolicy {
    val whitePkg = config.whiteList.all()
        .filter { it.enabled }
        .map { it.packageName }
        .toSet()
    val ffPkg = config.blackList.all()
        .filter { it.enabled }
        .map { it.packageName }
        .toSet()
    val imPkg = config.whiteList.all()
        .filter { it.enabled }
        .filter { it.mode == com.athena.xposed.model.ProtectionMode.IM_KEEPALIVE }
        .map { it.packageName }
        .toSet()
    return FreezePolicy(
        whitePkg = whitePkg,
        ffPkg = ffPkg,
        ffTimeoutMs = config.module.defaultFreezeTimeoutMs,
        imPkg = imPkg,
        imTimeoutMs = config.module.defaultImHeartbeatTimeoutMs,
    )
}

/**
 * 复制文本到系统剪贴板并弹出 Toast 提示。
 */
private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Athena FreezePolicy", text))
    Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
}


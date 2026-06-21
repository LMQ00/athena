package com.athena.xposed.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.athena.xposed.model.AppEntry

/**
 * 编辑单个 [AppEntry] 高级设置的对话框。
 *
 * 可编辑字段：
 *  - 进程名（逗号分隔，空表示匹配所有进程）
 *  - 正则匹配开关
 *  - 自定义冻结超时（ms，-1 = 不覆盖全局默认）
 *  - IM 心跳超时（ms，-1 = 不覆盖全局默认）
 *  - 备注
 *
 * 超时字段采用毫秒输入，避免与 [AppEntry] 内部表示之间反复换算。
 * UI 在 hint 中提示「-1 表示使用全局默认」。空输入按 -1 处理，避免误把
 * 超时改写为 0 导致应用立刻被冻结。
 *
 * @param entry 待编辑条目。
 * @param onDismiss 关闭回调（取消 / 删除后 / 保存后均会触发）。
 * @param onSave 保存回调，参数为编辑后的新条目。
 * @param onDelete 删除回调，参数为待删除条目的包名。
 */
@Composable
fun EditEntryDialog(
    entry: AppEntry,
    onDismiss: () -> Unit,
    onSave: (AppEntry) -> Unit,
    onDelete: (String) -> Unit,
) {
    // ---- 本地编辑态：每次打开对话框时以传入 entry 重置 ----
    var processNamesText by rememberSaveable(entry) {
        mutableStateOf(entry.processNames.joinToString(","))
    }
    var processRegex by rememberSaveable(entry) { mutableStateOf(entry.processRegex) }
    var freezeTimeoutText by rememberSaveable(entry) {
        mutableStateOf(entry.customFreezeTimeoutMs?.toString() ?: "-1")
    }
    var heartbeatTimeoutText by rememberSaveable(entry) {
        mutableStateOf(entry.imHeartbeatTimeoutMs.toString())
    }
    var noteText by rememberSaveable(entry) { mutableStateOf(entry.note.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑条目") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 应用名只读展示，便于用户确认在编辑哪个应用
                Text(
                    text = entry.appName.ifBlank { entry.packageName },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = entry.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = processNamesText,
                    onValueChange = { processNamesText = it },
                    label = { Text("进程名（逗号分隔）") },
                    supportingText = { Text("留空表示匹配该包名下所有进程") },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.padding(end = 16.dp)) {
                        Text("进程名按正则匹配", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "关闭时按精确字符串匹配",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = processRegex,
                        onCheckedChange = { processRegex = it },
                    )
                }

                OutlinedTextField(
                    value = freezeTimeoutText,
                    onValueChange = { freezeTimeoutText = it.filter { ch -> ch.isDigit() || ch == '-' } },
                    label = { Text("自定义冻结超时 (ms)") },
                    supportingText = { Text("-1 = 使用全局默认") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = heartbeatTimeoutText,
                    onValueChange = {
                        heartbeatTimeoutText = it.filter { ch -> ch.isDigit() || ch == '-' }
                    },
                    label = { Text("IM 心跳超时 (ms)") },
                    supportingText = { Text("-1 = 使用全局默认") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("备注") },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val updated = entry.copy(
                    processNames = parseProcessNames(processNamesText),
                    processRegex = processRegex,
                    customFreezeTimeoutMs = parseTimeout(freezeTimeoutText).let { if (it == -1L) null else it },
                    imHeartbeatTimeoutMs = parseTimeout(heartbeatTimeoutText),
                    note = noteText.trim().takeIf { it.isNotEmpty() },
                )
                onSave(updated)
            }) { Text("保存") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onDelete(entry.packageName) }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/**
 * 解析逗号分隔的进程名输入。
 *
 * - 自动去除空白条目与首尾空格
 * - 全空白输入返回空列表（表示匹配所有进程）
 */
private fun parseProcessNames(text: String): List<String> =
    text.split(',', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

/**
 * 解析超时输入。
 *
 * - 空串或非数字 → -1（不覆盖默认）
 * - 负数（含 -1）→ 原样保留，由上层 UI 语义「-1 = 不覆盖」承担含义
 */
private fun parseTimeout(text: String): Long =
    text.trim().toLongOrNull() ?: -1L

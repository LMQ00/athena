@file:OptIn(ExperimentalMaterial3Api::class)

package com.athena.xposed.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.athena.xposed.model.ProtectionMode

/**
 * 带说明的保护模式选择器（[ExposedDropdownMenuBox]）。
 *
 * 与单色 RadioButton 选择相比，下拉更节省纵向空间，且能在选中行直接
 * 展示该模式的简短说明，便于新手理解分类含义。
 *
 * 仅暴露三种「分类性」模式（白名单 / IM 保活 / 黑名单），
 * [ProtectionMode.CUSTOM_FREEZE_CONFIG] 属于叠加配置，不在此处选择。
 *
 * @param current 当前选中的 [ProtectionMode]。
 * @param onSelected 用户选择新模式时的回调。
 * @param label 输入框标签文案。
 * @param enabled 是否可交互。
 * @param modifier 外部布局修饰。
 */
@Composable
fun ProtectionModeSelector(
    current: ProtectionMode,
    onSelected: (ProtectionMode) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "保护模式",
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = remember {
        listOf(
            ProtectionMode.WHITELIST,
            ProtectionMode.IM_KEEPALIVE,
            ProtectionMode.BLACKLIST,
        )
    }
    val labels = remember {
        mapOf(
            ProtectionMode.WHITELIST to "白名单",
            ProtectionMode.IM_KEEPALIVE to "IM 保活",
            ProtectionMode.BLACKLIST to "黑名单",
        )
    }
    val descriptions = remember {
        mapOf(
            ProtectionMode.WHITELIST to "永不冻结，无论是否在前台",
            ProtectionMode.IM_KEEPALIVE to "心跳超时后才允许冻结",
            ProtectionMode.BLACKLIST to "进入后台即强制冻结",
        )
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = labels[current] ?: current.name,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        androidx.compose.foundation.layout.Column {
                            Text(
                                text = labels[mode] ?: mode.name,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = descriptions[mode] ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        onSelected(mode)
                        expanded = false
                    },
                )
            }
        }
    }
}

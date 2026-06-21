@file:OptIn(ExperimentalMaterial3Api::class)

package com.athena.xposed.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.athena.xposed.model.DefaultPolicy
import com.athena.xposed.ui.data.ConfigViewModel
import com.athena.xposed.ui.navigation.AboutRoute
import com.athena.xposed.ui.navigation.BlacklistRoute
import com.athena.xposed.ui.navigation.WhitelistRoute

/**
 * 首页 —— 设置主界面。
 *
 * 顶部为模块全局配置（总开关 / 默认策略 / 默认冻结超时），
 * 中部为快捷入口卡片，进入白名单 / 黑名单 / 关于页面。
 *
 * 所有控件均通过 [ConfigViewModel.state] 双向绑定：UI 改动调用
 * ViewModel 的 helper 方法，触发 SharedPreferences 写入并刷新 StateFlow；
 * Compose 端通过 [collectAsStateWithLifecycle] 订阅。
 */
@Composable
fun HomeScreen(navController: NavController) {
    val config by ConfigViewModel.state.collectAsStateWithLifecycle()
    val module = config.module

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---- 顶部标题 ----
        Text(
            text = "Athena",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "ColorOS 16 后台冻结管理",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---- 全局开关 ----
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(),
        ) {
            ListItem(
                headlineContent = { Text("启用模块") },
                supportingContent = { Text("关闭后所有 Hook 不生效") },
                trailingContent = {
                    Switch(
                        checked = module.globalEnabled,
                        onCheckedChange = ConfigViewModel::setGlobalEnabled,
                    )
                },
            )
        }

        // ---- 默认策略下拉 ----
        DefaultPolicyDropdown(
            current = module.defaultPolicy,
            onSelect = ConfigViewModel::setDefaultPolicy,
        )

        // ---- 默认冻结超时 ----
        DefaultFreezeTimeoutField(
            valueMs = module.defaultFreezeTimeoutMs,
            onValueChange = ConfigViewModel::setDefaultFreezeTimeoutMs,
        )

        // ---- 快捷入口 ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                QuickEntryItem(
                    icon = { Icon(Icons.Filled.VerifiedUser, contentDescription = null) },
                    title = "白名单管理",
                    subtitle = "永不冻结的应用列表",
                    onClick = { navController.navigate(WhitelistRoute) },
                )
                QuickEntryItem(
                    icon = { Icon(Icons.Filled.Checklist, contentDescription = null) },
                    title = "黑名单管理",
                    subtitle = "强制冻结的应用列表",
                    onClick = { navController.navigate(BlacklistRoute) },
                )
                QuickEntryItem(
                    icon = { Icon(Icons.Filled.Info, contentDescription = null) },
                    title = "关于",
                    subtitle = "版本 / 作者 / 仓库",
                    onClick = { navController.navigate(AboutRoute) },
                )
            }
        }
    }
}

/**
 * 默认策略下拉选择器。
 *
 * 使用 [ExposedDropdownMenuBox] 实现传统 Material 下拉，选项为 [DefaultPolicy]
 * 三个枚举值。
 */
@Composable
private fun DefaultPolicyDropdown(
    current: DefaultPolicy,
    onSelect: (DefaultPolicy) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = DefaultPolicy.entries
    val labels = remember(options) {
        mapOf(
            DefaultPolicy.FOLLOW_SYSTEM to "跟随系统",
            DefaultPolicy.FORCE_EXCLUDE to "强制排除（不冻结）",
            DefaultPolicy.FORCE_FREEZE to "强制冻结",
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "默认策略",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "应用未命中任何列表时的处理方式",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                TextField(
                    value = labels[current] ?: current.name,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("默认策略") },
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
                    options.forEach { policy ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(labels[policy] ?: policy.name) },
                            onClick = {
                                onSelect(policy)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 默认冻结超时数字输入。
 *
 * UI 以「秒」为单位展示，内部转换为毫秒。空输入或非法值不会写回，
 * 避免覆盖已有配置为 0。
 */


/**
 * 快捷入口列表项 —— 左图标 + 标题/副标题 + 右箭头，点击触发 [onClick]。
 */
@Composable
private fun QuickEntryItem(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        leadingContent = icon,
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            IconButton(onClick = onClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "进入$title",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 默认冻结超时数字输入。
 *
 * UI 以「秒」为单位展示，内部转换为毫秒。
 * 用户清空输入框时视为「不设置」，不会触发回写；
 * 失焦时若输入为空则重置为上次有效值。
 */
@Composable
private fun DefaultFreezeTimeoutField(
    valueMs: Long,
    onValueChange: (Long) -> Unit,
) {
    // 展示值为秒；初始由毫秒换算
    var text by remember(valueMs) {
        mutableStateOf((valueMs / 1000L).toString())
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "默认冻结超时",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "应用进入后台多久后被冻结（秒），留空表示不设置",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            OutlinedTextField(
                value = text,
                onValueChange = { input ->
                    text = input.filter { it.isDigit() }
                    val seconds = text.toLongOrNull()
                    if (seconds != null && seconds >= 0L) {
                        onValueChange(seconds * 1000L)
                    }
                    // 空输入：不触发回写，保持 text 为空让用户看到清空效果
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

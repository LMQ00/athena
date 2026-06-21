package com.athena.xposed.ui.screens.blacklist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.athena.xposed.model.AppEntry
import com.athena.xposed.model.ProtectionMode
import com.athena.xposed.ui.components.AddAppSheet
import com.athena.xposed.ui.components.AppListItem
import com.athena.xposed.ui.components.EditEntryDialog
import com.athena.xposed.ui.components.EmptyStateView
import com.athena.xposed.ui.components.ModeChip
import com.athena.xposed.ui.data.ConfigViewModel
import kotlinx.coroutines.launch

/**
 * 黑名单管理界面。
 *
 * 与 [com.athena.xposed.ui.screens.whitelist.WhitelistScreen] 共享同一套
 * 编辑组件（[AddAppSheet] / [EditEntryDialog] / [ModeChip] / [AppListItem]），
 * 但由于黑名单只有一种 [ProtectionMode.BLACKLIST]，无需 [PrimaryTabRow] 切换。
 *
 * 数据来源：
 *  - 列表来自 [ConfigViewModel.state] 的 `blackList` [EntrySet]；
 *  - 增删改通过 [ConfigViewModel] 的 helper 完成，所有持久化在 IO 线程执行，
 *    UI 仅消费不可变快照，保证线程安全。
 *
 * 交互：
 *  - 顶部「显示系统应用」Switch 控制 [AddAppSheet] 应用选择器是否包含系统应用；
 *  - 条目行尾提供 More 菜单（编辑 / 删除 / 启用切换）并支持长按唤起菜单；
 *  - 右下角 FAB 打开 [AddAppSheet]，默认模式为 [ProtectionMode.BLACKLIST]；
 *  - 空列表时显示 [EmptyStateView]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlacklistScreen(navController: NavController) {
    val config by ConfigViewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // 黑名单条目（按包名唯一）。直接使用 config.blackList.all()，
    // 该集合已在 EntrySet 中维护包名索引，无需再次去重。
    val entries: List<AppEntry> = remember(config) {
        config.blackList.all()
    }

    // 本地 UI 状态
    var showAddSheet by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<AppEntry?>(null) }
    var showSystemApps by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("黑名单管理") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddSheet = true },
                icon = { Icon(Icons.Outlined.Block, contentDescription = null) },
                text = { Text("添加黑名单") },
            )
        },
    ) { innerPadding ->
        if (entries.isEmpty()) {
            EmptyStateView(
                title = "暂无黑名单条目",
                subtitle = "点击右下角按钮添加需要强制冻结的应用",
                icon = Icons.Outlined.Block,
                modifier = Modifier.padding(innerPadding),
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 顶部「显示系统应用」开关：仅影响后续打开的 AddAppSheet 中的应用选择器
            item(key = "__header_show_system__") {
                ShowSystemAppsRow(
                    checked = showSystemApps,
                    onCheckedChange = { showSystemApps = it },
                )
                HorizontalDivider()
            }

            items(
                items = entries,
                key = { it.packageName },
            ) { entry ->
                BlacklistEntryRow(
                    entry = entry,
                    onToggleEnabled = { pkg ->
                        scope.launch {
                            ConfigViewModel.toggleEntryEnabled(pkg, inWhitelist = false)
                        }
                    },
                    onEdit = { editingEntry = entry },
                    onDelete = { pkg ->
                        scope.launch {
                            ConfigViewModel.removeFromBlackList(pkg)
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }

    // ---- 弹层 ----

    if (showAddSheet) {
        AddAppSheet(
            defaultMode = ProtectionMode.BLACKLIST,
            showSystemApps = showSystemApps,
            onDismiss = { showAddSheet = false },
            onConfirm = { pkg, name, mode ->
                scope.launch {
                    val entry = AppEntry(packageName = pkg, appName = name, mode = mode)
                    ConfigViewModel.addToBlackList(entry)
                }
                showAddSheet = false
            },
        )
    }

    editingEntry?.let { entry ->
        EditEntryDialog(
            entry = entry,
            onSave = { updated ->
                scope.launch {
                    // addToBlackList 会按包名覆盖，等价于「更新」
                    ConfigViewModel.addToBlackList(updated)
                }
                editingEntry = null
            },
            onDismiss = { editingEntry = null },
            onDelete = { pkg ->
                scope.launch {
                    ConfigViewModel.removeFromBlackList(pkg)
                }
                editingEntry = null
            },
        )
    }
}

/**
 * 顶部「显示系统应用」开关行。
 *
 * 该开关为本地 UI 状态，仅在打开 [AddAppSheet] 时传入，控制应用选择器是否
 * 列出系统应用。不持久化，避免污染全局配置。
 */
@Composable
private fun ShowSystemAppsRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = "显示系统应用",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "在添加应用时列出系统应用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

/**
 * 单个黑名单条目行。
 *
 * 使用 [AppListItem] 作为主体布局，尾部展示 [ModeChip]（黑名单）与 More 菜单按钮。
 * 长按或点击 More 按钮均会唤起 DropdownMenu，提供编辑 / 删除 / 启用切换操作。
 *
 * @param entry 当前条目快照。
 * @param onToggleEnabled 切换 enabled 状态。
 * @param onEdit 打开编辑对话框。
 * @param onDelete 按包名删除条目。
 */
@Composable
private fun BlacklistEntryRow(
    entry: AppEntry,
    onToggleEnabled: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth()) {
        AppListItem(
            entry = entry,
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ModeChip(mode = entry.mode)
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = "更多操作",
                        )
                    }
                }
            },
            onLongClick = { menuExpanded = true },
        )

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(if (entry.enabled) "禁用" else "启用") },
                onClick = {
                    menuExpanded = false
                    onToggleEnabled(entry.packageName)
                },
            )
            DropdownMenuItem(
                text = { Text("编辑") },
                onClick = {
                    menuExpanded = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = "删除",
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    menuExpanded = false
                    onDelete(entry.packageName)
                },
            )
        }
    }
}

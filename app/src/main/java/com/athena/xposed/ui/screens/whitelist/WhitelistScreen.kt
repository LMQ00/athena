@file:OptIn(ExperimentalMaterial3Api::class)

package com.athena.xposed.ui.screens.whitelist

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.athena.xposed.ui.data.ConfigViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 白名单管理页面。
 *
 * 三个 Tab 按 [ProtectionMode] 过滤 [com.athena.xposed.model.AthenaConfig.whiteList]
 * 条目集合（其中包含 WHITELIST 与 IM_KEEPALIVE 两类条目）：
 *  - 全部：所有白名单集合条目
 *  - 白名单：仅 [ProtectionMode.WHITELIST]
 *  - IM 保活：仅 [ProtectionMode.IM_KEEPALIVE]
 *
 * 列表项支持：
 *  - 启用/禁用 Switch（调用 [ConfigViewModel.toggleEntryEnabled]）
 *  - 右上 More 菜单 / 长按 → 编辑（[EditEntryDialog]）/ 删除
 *  - 顶部「显示系统应用」开关：默认隐藏已添加的系统应用条目
 *
 * 右下 FAB 打开 [AddAppSheet]，可同时添加白名单 / IM 保活 / 黑名单条目；
 * 黑名单条目会落到 [com.athena.xposed.model.AthenaConfig.blackList]，
 * 不在本页面列表中显示（在「黑名单」Tab 页查看）。
 */
@Composable
fun WhitelistScreen(navController: NavController) {
    val config by ConfigViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- 顶部 Tab 状态 ----
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = remember(config) { buildTabs(config.whiteList.all()) }

    // ---- 显示系统应用开关 + 系统包名集合 ----
    var showSystemApps by remember { mutableStateOf(false) }
    var systemPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(Unit) {
        systemPackages = withContext(Dispatchers.IO) { loadSystemPackages(context.packageManager) }
    }

    // ---- 弹层状态 ----
    var showAddSheet by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<AppEntry?>(null) }

    // ---- 当前 Tab 过滤后的条目 ----
    val allEntries = config.whiteList.all()
    val visibleEntries = remember(allEntries, selectedTab, showSystemApps, systemPackages) {
        val byMode = when (selectedTab) {
            1 -> allEntries.filter { it.mode == ProtectionMode.WHITELIST }
            2 -> allEntries.filter { it.mode == ProtectionMode.IM_KEEPALIVE }
            else -> allEntries
        }
        if (showSystemApps) byMode
        else byMode.filter { it.packageName !in systemPackages }
    }

    // ---- 当前 Tab 下系统应用数量（用于开关指示器） ----
    val systemAppCount = remember(allEntries, selectedTab, systemPackages) {
        val byMode = when (selectedTab) {
            1 -> allEntries.filter { it.mode == ProtectionMode.WHITELIST }
            2 -> allEntries.filter { it.mode == ProtectionMode.IM_KEEPALIVE }
            else -> allEntries
        }
        byMode.count { it.packageName in systemPackages }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("白名单管理") },
                    actions = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Text(
                                text = "系统应用",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Switch(
                                checked = showSystemApps,
                                onCheckedChange = { showSystemApps = it },
                                modifier = Modifier.padding(start = 8.dp),
                            )
                            if (showSystemApps && systemAppCount > 0) {
                                Text(
                                    text = "含 $systemAppCount 个系统应用",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    },
                )
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(tab.label) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddSheet = true }) {
                Icon(Icons.Filled.Add, contentDescription = "添加应用")
            }
        },
    ) { innerPadding ->
        if (visibleEntries.isEmpty()) {
            EmptyStateView(
                title = emptyTitleForTab(selectedTab),
                subtitle = "点击右下角 + 添加应用",
                modifier = Modifier.padding(innerPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                items(visibleEntries, key = { it.packageName }) { entry ->
                    AppListItem(
                        entry = entry,
                        onToggleEnabled = {
                            ConfigViewModel.toggleEntryEnabled(it, inWhitelist = true)
                        },
                        onEdit = { editingEntry = entry },
                        onDelete = { ConfigViewModel.removeFromWhiteList(it) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    // ---- 添加应用弹层 ----
    if (showAddSheet) {
        AddAppSheet(
            onDismiss = { showAddSheet = false },
            onConfirm = { pkg, name, mode ->
                scope.launch {
                    val entry = AppEntry(packageName = pkg, appName = name, mode = mode)
                    if (mode == ProtectionMode.BLACKLIST) {
                        ConfigViewModel.addToBlackList(entry)
                    } else {
                        ConfigViewModel.addToWhiteList(entry)
                    }
                }
            },
        )
    }

    // ---- 编辑条目弹层 ----
    editingEntry?.let { entry ->
        EditEntryDialog(
            entry = entry,
            onDismiss = { editingEntry = null },
            onSave = { updated ->
                scope.launch {
                    ConfigViewModel.update { cfg ->
                        val belongsInWhiteList = updated.mode == ProtectionMode.WHITELIST ||
                                updated.mode == ProtectionMode.IM_KEEPALIVE
                        val whiteList = cfg.whiteList.entries.toMutableList()
                        whiteList.removeAll { it.packageName == updated.packageName }
                        if (belongsInWhiteList) {
                            whiteList.add(updated)
                            cfg.copy(whiteList = com.athena.xposed.model.EntrySet(whiteList))
                        } else {
                            var newCfg = cfg.copy(whiteList = com.athena.xposed.model.EntrySet(whiteList))
                            when (updated.mode) {
                                ProtectionMode.BLACKLIST -> {
                                    val blackList = cfg.blackList.entries.toMutableList()
                                    blackList.removeAll { it.packageName == updated.packageName }
                                    blackList.add(updated)
                                    newCfg = newCfg.copy(blackList = com.athena.xposed.model.EntrySet(blackList))
                                }
                                ProtectionMode.CUSTOM_FREEZE_CONFIG -> {
                                    val custom = cfg.customEntries.entries.toMutableList()
                                    custom.removeAll { it.packageName == updated.packageName }
                                    custom.add(updated)
                                    newCfg = newCfg.copy(customEntries = com.athena.xposed.model.EntrySet(custom))
                                }
                                else -> {}
                            }
                            newCfg
                        }
                    }
                }
                editingEntry = null
            },
            onDelete = { pkg ->
                ConfigViewModel.removeFromWhiteList(pkg)
                editingEntry = null
            },
        )
    }
}

/**
 * 构建 3 个 Tab 的标签（含数量）。
 */
private fun buildTabs(all: List<AppEntry>): List<TabLabel> = listOf(
    TabLabel("全部(${all.size})"),
    TabLabel("白名单(${all.count { it.mode == ProtectionMode.WHITELIST }})"),
    TabLabel("IM 保活(${all.count { it.mode == ProtectionMode.IM_KEEPALIVE }})"),
)

/** Tab 标签载体。 */
private data class TabLabel(val label: String)

/**
 * 不同 Tab 下的空状态文案。
 */
private fun emptyTitleForTab(tab: Int): String = when (tab) {
    1 -> "白名单为空"
    2 -> "暂无 IM 保活应用"
    else -> "暂无受保护应用"
}

/**
 * 在 IO 线程加载系统应用包名集合，供「显示系统应用」开关过滤使用。
 */
private suspend fun loadSystemPackages(pm: PackageManager): Set<String> =
    withContext(Dispatchers.IO) {
        runCatching {
            pm.getInstalledApplications(PackageManager.MATCH_ALL)
                .asSequence()
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 }
                .map { it.packageName }
                .toSet()
        }.getOrDefault(emptySet())
    }

package com.swipeguard.xposed.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swipeguard.xposed.ui.data.SwipeGuardViewModel
import kotlinx.coroutines.launch

/**
 * SwipeGuard 主界面 —— 单屏。
 *
 * 顶部：全局开关
 * 中部：已保护 app 列表（系统默认带角标，可移除）
 * 底部 FAB：添加新 app（通过 PackageManager 选择）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeGuardScreen() {
    val uiState by SwipeGuardViewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SwipeGuard") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "添加应用")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // 全局开关
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("SwipeGuard", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (uiState.config.enabled) "白名单保护已启用" else "已关闭",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (uiState.config.enabled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.config.enabled,
                        onCheckedChange = { SwipeGuardViewModel.toggleEnabled() }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // 已保护 app 列表标题
            val effectiveApps = uiState.effectiveProtectedApps
            Text(
                "已保护应用 (${effectiveApps.size})",
                style = MaterialTheme.typography.titleSmall
            )

            Spacer(Modifier.height(8.dp))

            // 加载中 / 空状态
            if (uiState.isLoading) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "等待 system_server 初始化...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else if (effectiveApps.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        "还没有受保护的应用\n点击右下角 + 添加",
                        modifier = Modifier.padding(24.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(effectiveApps.sorted(), key = { it }) { pkg ->
                        val isSystemDefault = pkg in uiState.systemDefaults
                        val isUserAdded = pkg in uiState.config.userAdditions

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { showDeleteConfirm = pkg }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = pkg,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (isSystemDefault) {
                                        Text(
                                            text = "系统默认",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Text(
                                    "点击移除",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 添加应用对话框
    if (showAddDialog) {
        AddAppDialog(
            currentPackages = uiState.effectiveProtectedApps,
            onAdd = { pkg ->
                scope.launch { SwipeGuardViewModel.addPackage(pkg) }
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }

    // 删除确认
    showDeleteConfirm?.let { pkg ->
        val isSystemDefault = pkg in uiState.systemDefaults
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("确认移除") },
            text = {
                if (isSystemDefault) {
                    Text("「$pkg」是系统默认保护应用。移除后将不再受 SwipeGuard 保护。")
                } else {
                    Text("将「$pkg」从白名单移除？")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { SwipeGuardViewModel.removePackage(pkg) }
                    showDeleteConfirm = null
                }) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 添加应用对话框。
 * 显示已安装应用列表供用户选择。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAppDialog(
    currentPackages: Set<String>,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    
    val installedApps = remember(showSystemApps, currentPackages) {
        context.packageManager.getInstalledApplications(0)
            .filter {
                if (showSystemApps) true
                else (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
            }
            .filter { it.packageName !in currentPackages }
            .sortedBy { context.packageManager.getApplicationLabel(it).toString() }
    }

    val filteredApps = remember(searchQuery, installedApps) {
        if (searchQuery.isBlank()) installedApps
        else installedApps.filter {
            val label = context.packageManager.getApplicationLabel(it).toString()
            label.contains(searchQuery, ignoreCase = true) ||
            it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加保护应用") },
        text = {
            Column {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("搜索应用") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = showSystemApps,
                        onCheckedChange = { showSystemApps = it }
                    )
                    Text("显示系统应用", style = MaterialTheme.typography.bodySmall)
                }

                if (filteredApps.isEmpty()) {
                    Text(
                        "没有可添加的应用",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 400.dp)
                    ) {
                        items(filteredApps, key = { it.packageName }) { app ->
                            TextButton(
                                onClick = { onAdd(app.packageName) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    context.packageManager.getApplicationLabel(app).toString(),
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

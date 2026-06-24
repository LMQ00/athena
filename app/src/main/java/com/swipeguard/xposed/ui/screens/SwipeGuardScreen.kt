package com.swipeguard.xposed.ui.screens

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swipeguard.xposed.ui.data.SwipeGuardViewModel
import kotlinx.coroutines.launch

/**
 * SwipeGuard 主界面 —— 单屏。
 *
 * 顶栏：应用名 + Switch 开关
 * 主内容：已保护应用列表（图标 + 名 + 包名 + 标签）
 * FAB：添加新应用
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeGuardScreen() {
    val uiState by SwipeGuardViewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }

    val effectiveApps = uiState.effectiveProtectedApps

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("SwipeGuard", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "已保护 ${effectiveApps.size} 个应用",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    Switch(
                        checked = uiState.config.enabled,
                        onCheckedChange = { SwipeGuardViewModel.toggleEnabled() }
                    )
                    Spacer(Modifier.width(8.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "添加应用")
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (effectiveApps.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "还没有受保护的应用",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "点击右下角 + 添加",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp,
                        top = 8.dp, bottom = 88.dp // 避免 FAB 遮挡
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(effectiveApps.sorted(), key = { it }) { pkg ->
                        val isSystemDefault = pkg in uiState.config.systemDefaults
                        val isUserAdded = pkg in uiState.config.userAdditions

                        AppItemCard(
                            pkg = pkg,
                            isSystemDefault = isSystemDefault,
                            isUserAdded = isUserAdded,
                            onClick = { showDeleteConfirm = pkg }
                        )
                    }
                }
            }
        }
    }

    // 添加对话框
    if (showAddDialog) {
        AddAppDialog(
            currentPackages = effectiveApps,
            onAdd = { pkg ->
                scope.launch { SwipeGuardViewModel.addPackage(pkg) }
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }

    // 删除确认
    showDeleteConfirm?.let { pkg ->
        val appLabel = getAppLabel(context, pkg)
        val isSystemDefault = pkg in uiState.config.systemDefaults
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("确认移除") },
            text = {
                if (isSystemDefault) {
                    Text("「${appLabel}」是系统默认保护应用。移除后将不再受保护，可随时重新添加。")
                } else {
                    Text("将「${appLabel}」从白名单移除？")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { SwipeGuardViewModel.removePackage(pkg) }
                    showDeleteConfirm = null
                }) { Text("移除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text("取消") }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// 应用列表卡片
// ─────────────────────────────────────────────────────────────────

@Composable
private fun AppItemCard(
    pkg: String,
    isSystemDefault: Boolean,
    isUserAdded: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 应用图标
            AppIcon(pkg = pkg, size = 40)

            Spacer(Modifier.width(12.dp))

            // 名称 + 包名
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = getAppLabel(LocalContext.current, pkg),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = pkg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(8.dp))

            // 标签 + 移除
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isUserAdded) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Text(
                            "添加",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
                if (isSystemDefault) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            "系统",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    "移除",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// 应用图标组件
// ─────────────────────────────────────────────────────────────────

@Composable
private fun AppIcon(pkg: String, size: Int) {
    val context = LocalContext.current
    val drawable = remember(pkg) {
        try {
            // 用目标应用的上下文加载图标，确保主题属性正确解析
            val targetContext = context.createPackageContext(pkg, 0)
            val appInfo = targetContext.packageManager.getApplicationInfo(pkg, 0)
            if (appInfo.icon != 0) {
                targetContext.getDrawable(appInfo.icon)
            } else {
                context.packageManager.getApplicationIcon(pkg)
            }
        } catch (_: Exception) {
            null
        }
    }
    if (drawable != null) {
        AndroidView(
            factory = { ctx ->
                android.widget.ImageView(ctx).apply {
                    setImageDrawable(drawable)
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    // 确保不应用任何来自父级的 tint
                    imageTintList = null
                }
            },
            modifier = Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(8.dp))
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// 添加应用对话框
// ─────────────────────────────────────────────────────────────────

@Composable
private fun AddAppDialog(
    currentPackages: Set<String>,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    var selectedPkg by remember { mutableStateOf<String?>(null) }

    val installedApps by produceState<List<ApplicationInfo>>(
        initialValue = emptyList(),
        key1 = showSystemApps,
        key2 = currentPackages.size
    ) {
        withContext(Dispatchers.IO) {
            context.packageManager.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(0))
                .filter {
                    if (showSystemApps) true
                    else (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0
                }
                .filter { it.packageName !in currentPackages }
                .sortedBy { getAppLabel(context, it.packageName).lowercase() }
        }
    }

    val filteredApps = remember(searchQuery, installedApps) {
        if (searchQuery.isBlank()) installedApps
        else installedApps.filter { app ->
            val label = getAppLabel(context, app.packageName)
            label.contains(searchQuery, ignoreCase = true) ||
            app.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "添加保护应用",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 500.dp)) {
                // 搜索框
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("搜索应用名称或包名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(Modifier.height(8.dp))

                // 显示系统应用
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = showSystemApps,
                        onCheckedChange = { showSystemApps = it }
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("显示系统应用", style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(Modifier.height(4.dp))

                // 已选提示
                selectedPkg?.let { pkg ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "已选：${getAppLabel(context, pkg)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }

                // 应用列表
                if (filteredApps.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (searchQuery.isNotBlank()) "未找到匹配的应用"
                            else "没有可添加的应用",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(filteredApps, key = { it.packageName }) { app ->
                            val pkg = app.packageName
                            val label = getAppLabel(context, pkg)
                            val isSelected = pkg == selectedPkg

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { selectedPkg = pkg },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surface
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 图标
                                    AppIcon(pkg = pkg, size = 32)

                                    Spacer(Modifier.width(12.dp))

                                    // 名称 + 包名
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = pkg,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    // 选中标记
                                    if (isSelected) {
                                        Spacer(Modifier.width(8.dp))
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selectedPkg?.let { onAdd(it) } },
                enabled = selectedPkg != null
            ) { Text("确认添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ─────────────────────────────────────────────────────────────────
// 工具函数
// ─────────────────────────────────────────────────────────────────

private val appLabelCache = mutableMapOf<String, String>()

private fun getAppLabel(context: android.content.Context, pkg: String): String {
    appLabelCache[pkg]?.let { return it }
    val label = try {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(
            pkg,
            PackageManager.ApplicationInfoFlags.of(0)
        )
        pm.getApplicationLabel(appInfo).toString()
    } catch (_: Exception) {
        pkg
    }
    appLabelCache[pkg] = label
    return label
}

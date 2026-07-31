package com.swipeguard.xposed.ui.screens

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShieldOutlined
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swipeguard.xposed.ui.data.SwipeGuardViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SwipeGuard 主界面 —— 分组式 Material 3 设计。
 *
 * 顶栏：应用名 + 保护状态摘要 + 总开关
 * 主体：按「我的添加 / 系统默认」分组展示，每组带小标题与计数
 * 添加：ModalBottomSheet 多选搜索（非旧版 AlertDialog）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeGuardScreen() {
    val uiState by SwipeGuardViewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showAddSheet by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }

    val config = uiState.config
    val effectiveApps = config.effectiveProtectedApps
    val userAdded = effectiveApps.filter { it in config.userAdditions }.sorted()
    val systemDefaults = effectiveApps.filter { it !in config.userAdditions }.sorted()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("SwipeGuard", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (config.enabled) "已保护 ${effectiveApps.size} 个应用"
                            else "已暂停保护",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (config.enabled) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    }
                },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (config.enabled) "保护中" else "已关闭",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(4.dp))
                        Switch(
                            checked = config.enabled,
                            onCheckedChange = { SwipeGuardViewModel.toggleEnabled() }
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddSheet = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = CircleShape
            ) {
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
                EmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp,
                        top = 8.dp, bottom = 88.dp
                    )
                ) {
                    // ── 我的添加 ──
                    if (userAdded.isNotEmpty()) {
                        item(key = "header-user") {
                            SectionHeader(
                                title = "我的添加",
                                count = userAdded.size,
                                accent = true
                            )
                        }
                        items(userAdded, key = { "user-$it" }) { pkg ->
                            AppItemCard(
                                pkg = pkg,
                                isSystemDefault = false,
                                isUserAdded = true,
                                onDeleteClick = { showDeleteConfirm = pkg }
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                    }

                    // ── 系统默认 ──
                    if (systemDefaults.isNotEmpty()) {
                        item(key = "header-sys") {
                            SectionHeader(
                                title = "系统默认",
                                count = systemDefaults.size,
                                accent = false
                            )
                        }
                        items(systemDefaults, key = { "sys-$it" }) { pkg ->
                            AppItemCard(
                                pkg = pkg,
                                isSystemDefault = true,
                                isUserAdded = false,
                                onDeleteClick = { showDeleteConfirm = pkg }
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }
        }
    }

    // 添加应用 BottomSheet
    if (showAddSheet) {
        AddAppBottomSheet(
            currentPackages = effectiveApps,
            onAdd = { pkgs ->
                scope.launch { SwipeGuardViewModel.addPackages(pkgs) }
            },
            onDismiss = { showAddSheet = false }
        )
    }

    // 删除确认
    showDeleteConfirm?.let { pkg ->
        val appLabel = getAppLabel(context, pkg)
        val isSystemDefault = pkg in config.systemDefaults
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            icon = {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("移除「${appLabel}」") },
            text = {
                if (isSystemDefault) {
                    Text("这是系统默认保护的应用。移除后将不再受划卡保护，可随时重新添加。")
                } else {
                    Text("从白名单中移除该应用？")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { SwipeGuardViewModel.removePackage(pkg) }
                    showDeleteConfirm = null
                }) {
                    Text("移除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text("取消")
                }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// 分组小标题
// ─────────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String, count: Int, accent: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (accent) Icons.Filled.Shield else Icons.Filled.ShieldOutlined,
            contentDescription = null,
            tint = if (accent) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (accent) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                text = "$count",
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (accent) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// 应用卡片
// ─────────────────────────────────────────────────────────────────

@Composable
private fun AppItemCard(
    pkg: String,
    isSystemDefault: Boolean,
    isUserAdded: Boolean,
    onDeleteClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onDeleteClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 0.5.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(pkg = pkg, size = 44)

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = getAppLabel(LocalContext.current, pkg),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = pkg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isSystemDefault || isUserAdded) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (isSystemDefault) {
                            Tag(text = "系统", container = MaterialTheme.colorScheme.surfaceVariant,
                                content = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (isUserAdded) {
                            Tag(text = "添加", container = MaterialTheme.colorScheme.primaryContainer,
                                content = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            Icon(
                Icons.Filled.Delete,
                contentDescription = "移除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun Tag(text: String, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(5.dp), color = container) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = content
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// 应用图标
// ─────────────────────────────────────────────────────────────────

@Composable
private fun AppIcon(pkg: String, size: Int) {
    val context = LocalContext.current
    val drawable = remember(pkg) {
        try {
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
                    imageTintList = null
                }
            },
            modifier = Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(11.dp))
        )
    } else {
        val label = getAppLabel(context, pkg)
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// 空状态
// ─────────────────────────────────────────────────────────────────

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "暂无受保护应用",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "点击右下角 + 添加应用，防止划卡时被系统杀死",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// 添加应用 ModalBottomSheet
// ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAppBottomSheet(
    currentPackages: Set<String>,
    onAdd: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    var selectedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }

    var installedApps by remember { mutableStateOf<List<ApplicationInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(showSystemApps, currentPackages.size) {
        isLoading = true
        val apps = withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            context.packageManager.getInstalledApplications(0)
                .filter {
                    if (showSystemApps) true
                    else (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0
                }
                .filter { it.packageName !in currentPackages }
                .sortedBy { getAppLabel(context, it.packageName).lowercase() }
        }
        installedApps = apps
        isLoading = false
    }

    val filteredApps = remember(searchQuery, installedApps) {
        if (searchQuery.isBlank()) installedApps
        else installedApps.filter { app ->
            val label = getAppLabel(context, app.packageName)
            label.contains(searchQuery, ignoreCase = true) ||
            app.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Surface(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 4.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            ) {}
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
        ) {
            // 标题
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("添加保护应用", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(12.dp))

            // 搜索框
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索应用名称或包名") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(Modifier.height(6.dp))

            // 显示系统应用 + 已选计数（一行）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { showSystemApps = !showSystemApps }
                        .padding(vertical = 4.dp)
                ) {
                    Checkbox(
                        checked = showSystemApps,
                        onCheckedChange = { showSystemApps = it }
                    )
                    Spacer(Modifier.width(2.dp))
                    Text("显示系统应用", style = MaterialTheme.typography.bodyMedium)
                }
                if (selectedPackages.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                "已选 ${selectedPackages.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            } else if (filteredApps.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
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
                        val isSelected = pkg in selectedPackages

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    selectedPackages =
                                        if (pkg in selectedPackages) selectedPackages - pkg
                                        else selectedPackages + pkg
                                },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            } else Color.Transparent
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AppIcon(pkg = pkg, size = 32)
                                Spacer(Modifier.width(12.dp))
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
                                Checkbox(checked = isSelected, onCheckedChange = null)
                            }
                        }
                    }
                    item {
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            // 底部操作栏
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { onAdd(selectedPackages); onDismiss() },
                    enabled = selectedPackages.isNotEmpty()
                ) {
                    Text("确认添加 (${selectedPackages.size})")
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// 工具函数
// ─────────────────────────────────────────────────────────────────

private val appLabelCache = mutableMapOf<String, String>()

private fun getAppLabel(context: android.content.Context, pkg: String): String {
    appLabelCache[pkg]?.let { return it }
    val label = try {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val appInfo = pm.getApplicationInfo(pkg, 0)
        pm.getApplicationLabel(appInfo).toString()
    } catch (_: Exception) {
        pkg
    }
    appLabelCache[pkg] = label
    return label
}

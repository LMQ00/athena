package com.swipeguard.xposed.ui.screens

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swipeguard.xposed.ui.data.SwipeGuardViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SwipeGuard 主界面 —— LSPosed 风格重构。
 *
 * 顶栏：应用名 + 已保护计数 + Switch 开关
 * 主体：LSPosed 风格列表卡片（48dp 图标 + 应用名 + 包名 + 标签 + 滑动删除）
 * 底部弹出式添加搜索（ModalBottomSheet）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SwipeGuardScreen() {
    val uiState by SwipeGuardViewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showAddSheet by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }

    val effectiveApps = uiState.effectiveProtectedApps
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("SwipeGuard", fontWeight = FontWeight.SemiBold)
                        Text(
                            "已保护 ${effectiveApps.size} 个应用",
                            style = MaterialTheme.typography.labelSmall,
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
            FloatingActionButton(
                onClick = { showAddSheet = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
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
                // 空状态
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "暂无受保护应用",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "添加应用后可防止划卡时被系统杀死",
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
                        top = 8.dp, bottom = 88.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(effectiveApps.sorted(), key = { it }) { pkg ->
                        SwipeToDismissBox(
                            state = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    if (value == SwipeToDismissBoxValue.EndToStart) {
                                        showDeleteConfirm = pkg
                                        false // 不实际关闭，由删除确认弹窗决定
                                    } else false
                                }
                            ),
                            backgroundContent = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.errorContainer),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.padding(end = 20.dp)
                                    )
                                }
                            },
                            enableDismissFromStartToEnd = false,
                            gesturesEnabled = true,
                            modifier = Modifier.animateItem()
                        ) {
                            AppItemCard(
                                pkg = pkg,
                                isSystemDefault = pkg in uiState.config.systemDefaults,
                                isUserAdded = pkg in uiState.config.userAdditions,
                                onDeleteClick = { showDeleteConfirm = pkg }
                            )
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
            onAdd = { pkg ->
                scope.launch { SwipeGuardViewModel.addPackage(pkg) }
            },
            onDismiss = { showAddSheet = false }
        )
    }

    // 删除确认
    showDeleteConfirm?.let { pkg ->
        val appLabel = getAppLabel(context, pkg)
        val isSystemDefault = pkg in uiState.config.systemDefaults
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
// LSPosed 风格的应用卡片
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
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onDeleteClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.5.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // LSPosed 风格：48dp 大圆角图标
            AppIcon(pkg = pkg, size = 48)

            Spacer(Modifier.width(16.dp))

            // 应用信息
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
                // 标签行
                if (isSystemDefault || isUserAdded) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (isSystemDefault) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    "系统",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (isUserAdded) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer
                            ) {
                                Text(
                                    "添加",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            // 右侧删除指示
            Icon(
                Icons.Filled.Delete,
                contentDescription = "移除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier.size(20.dp)
            )
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
                .clip(RoundedCornerShape(12.dp))
        )
    } else {
        // 回退：显示首字母
        val label = getAppLabel(context, pkg)
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(12.dp))
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
// 添加应用 BottomSheet
// ─────────────────────────────────────────────────────────────────

@Composable
private fun AddAppBottomSheet(
    currentPackages: Set<String>,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    var selectedPkg by remember { mutableStateOf<String?>(null) }

    // 异步加载应用列表
    var installedApps by remember { mutableStateOf<List<ApplicationInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(showSystemApps, currentPackages.size) {
        isLoading = true
        val apps = withContext(Dispatchers.IO) {
            context.packageManager.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(0))
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("添加保护应用", fontWeight = FontWeight.SemiBold)
            }
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showSystemApps = !showSystemApps }
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
                                getAppLabel(context, pkg),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }

                // 应用列表
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("加载中…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else if (filteredApps.isEmpty()) {
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
                                        else Color.Transparent
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
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
                onClick = { selectedPkg?.let { onAdd(it); onDismiss() } },
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

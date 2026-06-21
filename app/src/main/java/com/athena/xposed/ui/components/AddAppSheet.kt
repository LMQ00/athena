@file:OptIn(ExperimentalMaterial3Api::class)

package com.athena.xposed.ui.components

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athena.xposed.model.ProtectionMode
import com.athena.xposed.ui.data.ConfigViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 已安装应用的精简视图，用于在 [AddAppSheet] 列表中渲染。
 *
 * 图标延迟到首次组合对应行时再解码，避免一次性把全部应用图标塞入内存。
 */
internal data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)

/**
 * 添加应用到保护列表的底部弹层。
 *
 * 流程：
 *  1. 异步加载已安装应用列表（[PackageManager.getInstalledApplications]），
 *     按应用名排序；
 *  2. 顶部搜索框过滤应用名 / 包名；
 *  3. 顶部「显示系统应用」开关控制是否包含系统应用；
 *  4. 列表中点击某个应用 → 展开保护模式单选；
 *  5. 已存在于白名单 / 黑名单的应用显示「已添加」角标且不可选；
 *  6. 点击「添加」回调 [onConfirm] 并关闭弹层。
 *
 * 已存在集合通过 [ConfigViewModel.state] 实时读取，避免传入大集合参数。
 *
 * @param onDismiss 关闭回调。
 * @param onConfirm 确认添加回调，参数为 (包名, 应用名, 保护模式)。
 */
@Composable
fun AddAppSheet(
    onDismiss: () -> Unit,
    onConfirm: (packageName: String, appName: String, mode: ProtectionMode) -> Unit,
    defaultMode: ProtectionMode = ProtectionMode.WHITELIST,
    showSystemApps: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val config by ConfigViewModel.state.collectAsStateWithLifecycle()

    // 已加入任一列表的包名集合，用于在列表中标记「已添加」
    val addedPackages: Set<String> = remember(config) {
        buildSet {
            config.whiteList.all().forEach { add(it.packageName) }
            config.blackList.all().forEach { add(it.packageName) }
        }
    }

    // ---- 已安装应用列表：异步加载一次 ----
    var installed by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        installed = withContext(Dispatchers.IO) {
            loadInstalledApps(context.packageManager)
        }
        loaded = true
    }

    // ---- 交互态 ----
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(showSystemApps) }
    var selectedPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedMode by rememberSaveable { mutableStateOf(defaultMode) }

    val filtered = remember(installed, query, showSystem) {
        val q = query.trim().lowercase()
        installed.asSequence()
            .filter { showSystem || !it.isSystem }
            .filter { q.isEmpty() || it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
            .toList()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "添加应用",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索应用名 / 包名") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("显示系统应用", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = showSystem, onCheckedChange = { showSystem = it })
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
            ) {
                if (!loaded) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("加载中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (filtered.isEmpty()) {
                    EmptyStateView(
                        title = if (query.isBlank()) "暂无应用" else "无匹配应用",
                        subtitle = if (query.isBlank()) null else "尝试更换关键字或打开系统应用过滤",
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(filtered, key = { it.packageName }) { app ->
                            AppPickerRow(
                                app = app,
                                alreadyAdded = app.packageName in addedPackages,
                                isSelected = app.packageName == selectedPackage,
                                selectedMode = selectedMode,
                                onSelect = {
                                    if (app.packageName != selectedPackage) {
                                        selectedMode = defaultMode
                                    }
                                    selectedPackage = app.packageName
                                },
                                onModeChange = { selectedMode = it },
                            )
                        }
                    }
                }
            }

            // ---- 确认按钮：仅当选中且未已添加时可用 ----
            val selectedApp = filtered.firstOrNull { it.packageName == selectedPackage }
            val canConfirm = selectedApp != null && selectedApp.packageName !in addedPackages
            Button(
                onClick = {
                    val app = selectedApp ?: return@Button
                    onConfirm(app.packageName, app.label, selectedMode)
                    onDismiss()
                },
                enabled = canConfirm,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            ) {
                Text(if (canConfirm) "添加到「${labelForMode(selectedMode)}」" else "请选择应用")
            }
        }
    }
}

/**
 * 单个应用选择行：图标 + 名称/包名 + 「已添加」角标；
 * 选中时展开保护模式单选区。
 */
@Composable
private fun AppPickerRow(
    app: InstalledApp,
    alreadyAdded: Boolean,
    isSelected: Boolean,
    selectedMode: ProtectionMode,
    onSelect: () -> Unit,
    onModeChange: (ProtectionMode) -> Unit,
) {
    val pm = LocalContext.current.packageManager
    val iconBitmap = remember(app.packageName) {
        runCatching {
            pm.getApplicationIcon(app.packageName)
                .toBitmap()
                .asImageBitmap()
        }.getOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (alreadyAdded) 0.5f else 1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (iconBitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = iconBitmap,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                )
            } else {
                Box(modifier = Modifier.size(36.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = app.label.ifBlank { app.packageName },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (alreadyAdded) {
                        Text(
                            text = "已添加",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!alreadyAdded) {
                RadioButton(selected = isSelected, onClick = onSelect)
            }
        }

        if (isSelected && !alreadyAdded) {
            ModeRadioGroup(
                selected = selectedMode,
                onSelect = onModeChange,
                modifier = Modifier.padding(start = 48.dp, top = 4.dp),
            )
        }
    }
}

/**
 * 保护模式单选组：白名单 / IM 保活 / 黑名单。
 */
@Composable
private fun ModeRadioGroup(
    selected: ProtectionMode,
    onSelect: (ProtectionMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = remember {
        listOf(
            ProtectionMode.WHITELIST to "白名单",
            ProtectionMode.IM_KEEPALIVE to "IM 保活",
            ProtectionMode.BLACKLIST to "黑名单",
        )
    }
    Column(modifier = modifier) {
        options.forEach { (mode, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selected == mode,
                    onClick = { onSelect(mode) },
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/** 模式 → 中文标签，用于按钮文案。 */
private fun labelForMode(mode: ProtectionMode): String = when (mode) {
    ProtectionMode.WHITELIST -> "白名单"
    ProtectionMode.IM_KEEPALIVE -> "IM 保活"
    ProtectionMode.BLACKLIST -> "黑名单"
    ProtectionMode.CUSTOM_FREEZE_CONFIG -> "自定义"
}

/**
 * 在 IO 线程加载已安装应用并按应用名排序。
 *
 * - 使用 [PackageManager.MATCH_ALL] 兼容多用户场景下被冻结的应用；
 * - [ApplicationInfo.FLAG_SYSTEM] 用于区分系统应用，受 UI 开关控制；
 * - label 解码可能抛 NameNotFoundException，已用 runCatching 兜底。
 */
private fun loadInstalledApps(pm: PackageManager): List<InstalledApp> =
    runCatching {
        pm.getInstalledApplications(PackageManager.MATCH_ALL)
            .asSequence()
            .map { info ->
                InstalledApp(
                    packageName = info.packageName,
                    label = runCatching { pm.getApplicationLabel(info).toString() }
                        .getOrDefault(info.packageName),
                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toList()
    }.getOrDefault(emptyList())

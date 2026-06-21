package com.athena.xposed.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.athena.xposed.model.AppEntry

/**
 * 通用应用条目行组件。
 *
 * 支持两种使用模式：
 *  - **白名单模式**：传入 [onToggleEnabled] / [onEdit] / [onDelete]，组件会自动
 *    渲染尾部 Switch + More 菜单。
 *  - **黑名单/自定义模式**：传入 [trailingContent] 和/或 [onLongClick] 自定义尾部
 *    内容和长按行为，菜单由外部自行管理。
 *
 * 两种模式可混合使用——同时传入 [onToggleEnabled] 与 [trailingContent] 时，
 * [trailingContent] 替换默认尾部布局（Switch + More 按钮），但 [onToggleEnabled]
 * 仍可通过外部自定义的尾部触发。
 *
 * @param entry 条目数据。
 * @param onToggleEnabled 切换启用态的回调（包名），传入时显示 Switch。
 * @param onEdit 编辑回调（条目本身），传入时 More 菜单出现「编辑」项。
 * @param onDelete 删除回调（包名），传入时 More 菜单出现「删除」项。
 * @param trailingContent 自定义尾部 Composable，非 null 时替换默认尾部布局。
 * @param onLongClick 长按回调，非 null 时启用长按手势。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppListItem(
    entry: AppEntry,
    onToggleEnabled: ((String) -> Unit)? = null,
    onEdit: ((AppEntry) -> Unit)? = null,
    onDelete: ((String) -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val pm = LocalContext.current.packageManager
    var menuExpanded by remember { mutableStateOf(false) }

    val iconBitmap = remember(entry.packageName) {
        runCatching {
            pm.getApplicationIcon(entry.packageName).toBitmap().asImageBitmap()
        }.getOrNull()
    }

    val hasDefaultMenu = onEdit != null || onDelete != null

    ListItem(
        modifier = Modifier.combinedClickable(
            onClick = {},
            onLongClick = onLongClick ?: { if (hasDefaultMenu) menuExpanded = true },
        ),
        leadingContent = {
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                )
            } else {
                Box(modifier = Modifier.size(40.dp))
            }
        },
        headlineContent = {
            Text(
                text = entry.appName.ifBlank { entry.packageName },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Text(
                    text = entry.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailingContent = {
            trailingContent?.invoke() ?: run {
                if (onToggleEnabled != null || hasDefaultMenu) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (onToggleEnabled != null) {
                            Switch(
                                checked = entry.enabled,
                                onCheckedChange = { onToggleEnabled(entry.packageName) },
                            )
                        }
                        if (hasDefaultMenu) {
                            Box {
                                IconButton(onClick = { menuExpanded = true }) {
                                    Icon(
                                        Icons.Filled.MoreVert,
                                        contentDescription = "更多操作",
                                    )
                                }
                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false },
                                ) {
                                    if (onEdit != null) {
                                        DropdownMenuItem(
                                            text = { Text("编辑") },
                                            onClick = {
                                                menuExpanded = false
                                                onEdit(entry)
                                            },
                                        )
                                    }
                                    if (onDelete != null) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    "删除",
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
                        }
                    }
                }
            }
        },
    )
}

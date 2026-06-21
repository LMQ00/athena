package com.athena.xposed.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.athena.xposed.model.ProtectionMode

/**
 * 彩色 [ProtectionMode] 标签 Chip。
 *
 * 用配色直观区分条目所属分类，方便用户在长列表中快速辨识：
 *  - [ProtectionMode.WHITELIST] → 绿色 "白名单"
 *  - [ProtectionMode.BLACKLIST] → 红色 "黑名单"
 *  - [ProtectionMode.IM_KEEPALIVE] → 紫色 "IM 保活"
 *  - [ProtectionMode.CUSTOM_FREEZE_CONFIG] → 橙色 "自定义"
 *
 * 配色取自 [MaterialTheme.colorScheme] 上的 container/on-container 配对，
 * 在亮/暗主题下均保持可读对比度。
 *
 * @param mode 保护模式。
 * @param enabled 是否启用态；为 false 时整体降透明度，用于禁用条目的视觉提示。
 * @param modifier 外部布局修饰。
 * @param contentPadding 文本内边距，便于在不同密度下列表项里复用。
 */
@Composable
fun ModeChip(
    mode: ProtectionMode,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
) {
    val scheme = MaterialTheme.colorScheme
    val style = remember(mode) { styleFor(mode) }

    Surface(
        modifier = modifier.alpha(if (enabled) 1f else 0.45f),
        shape = RoundedCornerShape(50),
        color = style.containerColor(scheme),
        contentColor = style.contentColor(scheme),
    ) {
        Text(
            text = style.label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            modifier = Modifier.padding(contentPadding),
        )
    }
}

/** 模式对应的展示文案与取色规则。 */
private data class ModeStyle(
    val label: String,
    val containerColor: (androidx.compose.material3.ColorScheme) -> Color,
    val contentColor: (androidx.compose.material3.ColorScheme) -> Color,
)

/** 返回给定 [ProtectionMode] 的展示样式。 */
private fun styleFor(mode: ProtectionMode): ModeStyle = when (mode) {
    ProtectionMode.WHITELIST -> ModeStyle(
        label = "白名单",
        containerColor = { it.primaryContainer },
        contentColor = { it.onPrimaryContainer },
    )
    ProtectionMode.BLACKLIST -> ModeStyle(
        label = "黑名单",
        containerColor = { it.errorContainer },
        contentColor = { it.onErrorContainer },
    )
    ProtectionMode.IM_KEEPALIVE -> ModeStyle(
        label = "IM 保活",
        containerColor = { it.tertiaryContainer },
        contentColor = { it.onTertiaryContainer },
    )
    ProtectionMode.CUSTOM_FREEZE_CONFIG -> ModeStyle(
        label = "自定义",
        containerColor = { it.secondaryContainer },
        contentColor = { it.onSecondaryContainer },
    )
}

package com.assetstudio.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.assetstudio.mobile.MainViewModel

/** 全屏加载遮罩 */
@Composable
fun LoadingOverlay(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator()
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** 键值信息行 */
@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End
        )
    }
}

/** 类型徽标圆点（按资产类型着色） */
@Composable
fun TypeDot(typeValue: Int, modifier: Modifier = Modifier) {
    val color = when {
        typeValue == 28 || typeValue == 187 -> Color(0xFF7E57C2)      // 贴图
        typeValue == 213 || typeValue == 68 -> Color(0xFFEC407A)      // Sprite
        typeValue == 49 -> Color(0xFF26A69A)                          // 文本
        typeValue == 83 -> Color(0xFFFF7043)                          // 音频
        typeValue == 114 || typeValue == 115 -> Color(0xFF42A5F5)     // 脚本
        typeValue == 1 -> Color(0xFF66BB6A)                           // GameObject
        typeValue == 43 -> Color(0xFF8D6E63)                          // Mesh
        else -> Color(0xFF9E9E9E)
    }
    Box(
        modifier = modifier
            .size(12.dp)
            .background(color, CircleShape)
    )
}

/** 空状态占位 */
@Composable
fun EmptyPlaceholder(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}

fun formatBytes(size: Long): String = when {
    size >= 1 shl 20 -> "%.2f MB".format(size / 1048576.0)
    size >= 1 shl 10 -> "%.2f KB".format(size / 1024.0)
    else -> "$size B"
}

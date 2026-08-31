package com.assetstudio.mobile.ui.browser

import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

/*
 * 应用内文件选择器：替代系统 SAF「打开文件」对话框。
 *
 * 与 FileSaveDialog 配对：一个负责选文件（加载 bundle / 替换贴图 / 替换模型），
 * 一个负责存文件。全部走 java.io.File 直读直写，绕开 SAF 的 MIME 陷阱。
 *
 * 功能：
 * - 从外部存储根逐级浏览（不可读时回退应用专属目录）
 * - 单选：点文件即返回；多选：勾选后底部「打开 (N)」确认
 * - 可选扩展名过滤（小写比较；null = 不过滤）
 * - 选文件夹模式（pickDirectory）：只列目录，确认返回当前目录
 * - 快捷访问：长按文件夹收藏，点击收藏条直达目录（长按收藏条移除）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilePickerDialog(
    extensions: Set<String>? = null,
    allowMultiple: Boolean = false,
    pickDirectory: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (List<File>) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 起始目录：外部存储根；无法列出时退到应用专属外部目录
    val startDir = remember {
        val root = Environment.getExternalStorageDirectory()
        if (root.isDirectory && root.canRead()) root
        else context.getExternalFilesDir(null) ?: File("/sdcard")
    }

    var currentDir by remember { mutableStateOf(startDir) }
    var dirs by remember { mutableStateOf<List<File>>(emptyList()) }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var listError by remember { mutableStateOf<String?>(null) }
    // 多选已勾选的文件绝对路径
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 快捷访问（持久化收藏目录）
    var quickAccess by remember { mutableStateOf(QuickAccessStore.load(context)) }

    /** 长按文件夹 → 收藏到快捷访问 */
    fun addToQuickAccess(dir: File) {
        quickAccess = QuickAccessStore.add(context, dir.absolutePath)
        Toast.makeText(context, "已添加到快捷访问：${dir.name}", Toast.LENGTH_SHORT).show()
    }

    /** 点击快捷访问条 → 跳转（目录被删则移除该收藏） */
    fun jumpTo(path: String) {
        val dir = File(path)
        if (dir.isDirectory) {
            currentDir = dir
        } else {
            quickAccess = QuickAccessStore.remove(context, path)
            Toast.makeText(context, "目录已不存在，已从快捷访问移除", Toast.LENGTH_SHORT).show()
        }
    }

    // 目录列表（进入目录后重刷）；选文件夹模式不列文件
    LaunchedEffect(currentDir, pickDirectory) {
        val listed = currentDir.listFiles()
        if (listed == null) {
            listError = "无法读取该目录（权限不足？）"
            dirs = emptyList()
            files = emptyList()
        } else {
            listError = null
            val visible = listed.filter { !it.name.startsWith(".") }
            dirs = visible.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
            files = if (pickDirectory) emptyList()
            else visible
                .filter { it.isFile && (extensions == null || extensions.contains(extOf(it))) }
                .sortedBy { it.name.lowercase() }
            selected = selected.filter { path -> files.any { it.absolutePath == path } }.toSet()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    pickDirectory -> "选择文件夹"
                    allowMultiple -> "选择文件（可多选）"
                    else -> "选择文件"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // ---------- 快捷访问条 ----------
                QuickAccessRow(
                    entries = quickAccess,
                    onJump = { jumpTo(it) },
                    onRemove = { path ->
                        quickAccess = QuickAccessStore.remove(context, path)
                        Toast.makeText(context, "已从快捷访问移除：${File(path).name}", Toast.LENGTH_SHORT).show()
                    }
                )

                // ---------- 路径栏 + 返回上级 ----------
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = { currentDir.parentFile?.let { currentDir = it } },
                        enabled = currentDir.parentFile != null
                    ) {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = "返回上级")
                    }
                    Text(
                        currentDir.absolutePath,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState())
                    )
                }

                // ---------- 列表 ----------
                if (listError != null) {
                    Text(
                        listError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (dirs.isEmpty() && files.isEmpty() && listError == null) {
                        Text(
                            if (pickDirectory) "（无子文件夹）" else "（无匹配文件）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 340.dp)
                        ) {
                            items(dirs, key = { "d_" + it.absolutePath }) { dir ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = { currentDir = dir },
                                            onLongClick = { addToQuickAccess(dir) }
                                        )
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        dir.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            items(files, key = { "f_" + it.absolutePath }) { file ->
                                val checked = file.absolutePath in selected
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (allowMultiple) {
                                                selected = if (checked) {
                                                    selected - file.absolutePath
                                                } else {
                                                    selected + file.absolutePath
                                                }
                                            } else {
                                                onConfirm(listOf(file))
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    if (allowMultiple) {
                                        Checkbox(
                                            checked = checked,
                                            onCheckedChange = {
                                                selected = if (checked) {
                                                    selected - file.absolutePath
                                                } else {
                                                    selected + file.absolutePath
                                                }
                                            }
                                        )
                                    }
                                    Icon(
                                        Icons.Filled.Description,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            file.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            formatFileSize(file.length()),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (extensions != null) {
                    Text(
                        "仅显示：${extensions.joinToString(" / ") { ".$it" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            when {
                pickDirectory -> {
                    Button(onClick = { onConfirm(listOf(currentDir)) }) {
                        Text("选择此文件夹")
                    }
                }
                allowMultiple -> {
                    Button(
                        onClick = {
                            val picked = files.filter { it.absolutePath in selected }
                            if (picked.isNotEmpty()) onConfirm(picked)
                        },
                        enabled = selected.isNotEmpty()
                    ) {
                        Text("打开 (${selected.size})")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun extOf(file: File): String =
    file.name.substringAfterLast('.', "").lowercase()

internal fun formatFileSize(size: Long): String {
    val kb = size / 1024.0
    val mb = size / 1048576.0
    return when {
        size >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", mb)
        size >= 1L shl 10 -> String.format(java.util.Locale.US, "%.1f KB", kb)
        else -> "$size B"
    }
}

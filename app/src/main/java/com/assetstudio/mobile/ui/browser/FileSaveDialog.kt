package com.assetstudio.mobile.ui.browser

import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import java.io.File

/*
 * 应用内文件保存管理器：替代系统 SAF「另存为」对话框。
 *
 * 背景：SAF 的 CreateDocument(mime) 在各厂商系统上会按 MIME 强制改后缀
 * （text/plain→.txt、octet-stream→.bin），OBJ 导出后缀永远保不住。
 * 此组件直接浏览公共存储目录并 java.io.File 直写，后缀完全由文件名决定。
 *
 * 功能：
 * - 从外部存储根（不可写时回退应用专属目录）逐级浏览
 * - 新建文件夹、面包屑路径、返回上级
 * - 文件名可编辑（预填建议名含后缀）、同名覆盖确认
 * - 快捷访问：长按文件夹收藏，点击收藏条直达目录（长按收藏条移除）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileSaveDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (File) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 起始目录：外部存储根；无法列出时退到应用专属外部目录（必然可写）
    val startDir = remember {
        val root = Environment.getExternalStorageDirectory()
        if (root.isDirectory && root.canRead()) root
        else context.getExternalFilesDir(null) ?: File("/sdcard")
    }

    var currentDir by remember { mutableStateOf(startDir) }
    var name by remember { mutableStateOf(initialName) }
    var dirs by remember { mutableStateOf<List<File>>(emptyList()) }
    var listError by remember { mutableStateOf<String?>(null) }
    var pendingOverwrite by remember { mutableStateOf<File?>(null) }
    var showMkdir by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf<String?>(null) }
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

    // 目录列表（进入目录/新建后重刷）
    LaunchedEffect(currentDir) {
        val listed = currentDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
        if (listed == null) {
            listError = "无法读取该目录（权限不足？）"
            dirs = emptyList()
        } else {
            listError = null
            dirs = listed
        }
    }

    fun trySave() {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            nameError = "文件名不能为空"
            return
        }
        if (trimmed.contains('/') || trimmed.contains('\\')) {
            nameError = "文件名不能包含路径分隔符"
            return
        }
        nameError = null
        val target = File(currentDir, trimmed)
        if (target.exists()) {
            pendingOverwrite = target
        } else {
            onConfirm(target)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存到…") },
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

                // ---------- 路径栏 + 返回上级 + 新建 ----------
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
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState())
                    )
                    IconButton(onClick = { showMkdir = true }) {
                        Icon(Icons.Filled.CreateNewFolder, contentDescription = "新建文件夹")
                    }
                }

                // ---------- 目录列表 ----------
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
                    if (dirs.isEmpty() && listError == null) {
                        Text(
                            "（空目录）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp)
                        ) {
                            items(dirs, key = { it.absolutePath }) { dir ->
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
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }

                // ---------- 文件名 ----------
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; nameError = null },
                    label = { Text("文件名（含后缀）") },
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { trySave() }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )

    // ---------- 覆盖确认 ----------
    pendingOverwrite?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingOverwrite = null },
            title = { Text("覆盖文件？") },
            text = { Text("已存在同名文件：\n${target.name}\n（位于 ${currentDir.absolutePath}）") },
            confirmButton = {
                TextButton(onClick = {
                    pendingOverwrite = null
                    onConfirm(target)
                }) { Text("覆盖") }
            },
            dismissButton = {
                TextButton(onClick = { pendingOverwrite = null }) { Text("取消") }
            }
        )
    }

    // ---------- 新建文件夹 ----------
    if (showMkdir) {
        var newDirName by remember { mutableStateOf("") }
        var mkdirError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showMkdir = false },
            title = { Text("新建文件夹") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newDirName,
                        onValueChange = { newDirName = it; mkdirError = null },
                        label = { Text("文件夹名称") },
                        isError = mkdirError != null,
                        supportingText = mkdirError?.let { { Text(it) } },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = newDirName.trim()
                    if (trimmed.isEmpty() || trimmed.contains('/')) {
                        mkdirError = "名称无效"
                        return@TextButton
                    }
                    val dir = File(currentDir, trimmed)
                    if (dir.exists()) {
                        mkdirError = "已存在同名文件夹"
                    } else if (dir.mkdirs()) {
                        showMkdir = false
                        currentDir = dir
                    } else {
                        mkdirError = "创建失败（权限不足？）"
                    }
                }) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showMkdir = false }) { Text("取消") }
            }
        )
    }
}

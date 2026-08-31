package com.assetstudio.mobile.ui.browser

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

/*
 * 快捷访问（收藏文件夹）：应用内文件管理器的公共组件。
 *
 * - QuickAccessStore：SharedPreferences 持久化（保持添加顺序，容量上限 12 条）
 * - QuickAccessRow：横向滚动的收藏夹条
 *     · 点击条目 → 直接跳转该目录
 *     · 长按条目 → 从快捷访问移除
 * 文件夹列表中「长按文件夹」即可添加（见 FilePickerDialog / FileSaveDialog）。
 */

/** 快捷访问持久化存储 */
object QuickAccessStore {

    private const val PREFS_NAME = "quick_access"
    private const val KEY_PATHS = "paths"
    internal const val MAX_ENTRIES = 12

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 纯逻辑（无 Android 依赖，可单测）：加入收藏 → 去重、最新在前、超上限淘汰最旧 */
    internal fun merge(current: List<String>, newPath: String): List<String> {
        if (newPath in current) return current
        val result = ArrayList<String>(current.size + 1)
        result.add(newPath)
        result.addAll(current)
        while (result.size > MAX_ENTRIES) result.removeAt(result.size - 1)
        return result
    }

    /** 读取收藏目录（过滤已不存在的路径） */
    fun load(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_PATHS, null) ?: return emptyList()
        return raw.split('\n')
            .filter { it.isNotBlank() && File(it).isDirectory }
    }

    /** 添加目录（去重；最新在前；超上限淘汰最旧的），返回更新后的列表 */
    fun add(context: Context, path: String): List<String> {
        val updated = merge(load(context), path)
        prefs(context).edit().putString(KEY_PATHS, updated.joinToString("\n")).apply()
        return updated
    }

    /** 移除目录，返回更新后的列表 */
    fun remove(context: Context, path: String): List<String> {
        val updated = load(context).filter { it != path }
        prefs(context).edit().putString(KEY_PATHS, updated.joinToString("\n")).apply()
        return updated
    }
}

/**
 * 快捷访问条：横向滚动的收藏目录胶囊。
 * @param entries 收藏的目录绝对路径（按最近添加优先）
 * @param onJump 点击条目跳转
 * @param onRemove 长按条目移除
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuickAccessRow(
    entries: List<String>,
    onJump: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    if (entries.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "快捷访问（长按文件夹添加）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 2.dp)
        ) {
            entries.forEach { path ->
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.combinedClickable(
                        onClick = { onJump(path) },
                        onLongClick = { onRemove(path) }
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            Icons.Filled.FolderSpecial,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            File(path).name.ifEmpty { path },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

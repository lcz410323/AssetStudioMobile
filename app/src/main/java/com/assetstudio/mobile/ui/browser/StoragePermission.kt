package com.assetstudio.mobile.ui.browser

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/*
 * 存储权限：应用内文件管理器需要直写公共存储。
 *
 * - Android 11+（API 30）：MANAGE_EXTERNAL_STORAGE「所有文件访问」，
 *   无法弹窗请求，只能跳系统设置页由用户手动开启；
 *   从设置页返回（onResume/ActivityResult 回调）后重新检查状态。
 * - Android 8~10：传统 WRITE_EXTERNAL_STORAGE 运行时弹窗。
 */
object StoragePermission {

    /** 是否已拥有直写公共存储的权限 */
    fun has(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            granted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Android 11+ 跳「所有文件访问」设置页的 Intent */
    fun allFilesAccessIntent(context: Context): Intent {
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        return intent.resolveActivity(context.packageManager)?.let { intent }
            ?: Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    }
}

/**
 * 权限状态 + 请求入口。
 *
 * 用法：
 *   val (granted, request) = rememberStoragePermission()
 *   if (!granted) request() else 打开文件管理器
 *
 * Android 11+ 从设置页返回后自动重新检查；8~10 弹系统权限窗，结果即时回传。
 */
@Composable
fun rememberStoragePermission(): Pair<Boolean, () -> Unit> {
    val context = androidx.compose.ui.platform.LocalContext.current
    var granted by remember { mutableStateOf(StoragePermission.has(context)) }

    // Android 8~10：系统权限弹窗
    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it || StoragePermission.has(context) }

    // Android 11+：跳设置页，返回时重查
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { granted = StoragePermission.has(context) }

    // 组合期间（从设置页切回）兜底刷新一次
    androidx.compose.runtime.LaunchedEffect(Unit) {
        granted = StoragePermission.has(context)
    }

    val request: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching { settingsLauncher.launch(StoragePermission.allFilesAccessIntent(context)) }
            // 设置页返回后由 settingsLauncher 回调刷新
        } else {
            runtimeLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
    return granted to request
}

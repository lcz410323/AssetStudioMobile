package com.assetstudio.mobile.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.assetstudio.mobile.AssetItem
import com.assetstudio.mobile.LoadState
import com.assetstudio.mobile.MainViewModel
import com.assetstudio.mobile.core.crypto.EncryptedBundleDecoder
import com.assetstudio.mobile.ui.browser.FilePickerDialog
import com.assetstudio.mobile.ui.browser.rememberStoragePermission
import com.assetstudio.mobile.ui.components.InfoRow

/*
 * 主页：选择文件 → 加载 → 查看资产列表
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onOpenList: () -> Unit
) {
    val context = LocalContext.current
    val loadState by viewModel.loadState.collectAsState()
    val fileName by viewModel.loadedFileName.collectAsState()
    val assets by viewModel.assets.collectAsState()
    val errors by viewModel.loadErrors.collectAsState()
    val decryptNote by viewModel.decryptNote.collectAsState()
    val mcpStatus by viewModel.mcpStatus.collectAsState()
    val mcpStarting by viewModel.mcpStarting.collectAsState()
    val toast by viewModel.toast.collectAsState()
    val folderScan by viewModel.folderScan.collectAsState()
    val folderSummary by viewModel.folderSummary.collectAsState()
    val pendingRemaining by viewModel.pendingRemainingFiles.collectAsState()
    // v1.10.0：批次已彻底隐形（纯内部实现）——主页不再收集/展示任何批次状态
    var versionInput by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    // 手动解密对话框
    var showDecryptDialog by remember { mutableStateOf(false) }
    // 顶部菜单弹出的对话框
    var showSettings by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(toast) {
        if (toast != null) {
            snackbar.showSnackbar(toast!!)
            viewModel.consumeToast()
        }
    }

    // ---------- 应用内文件选择（替代 SAF OpenMultipleDocuments） ----------
    val (storageGranted, requestStorage) = rememberStoragePermission()
    var showFilePicker by remember { mutableStateOf(false) }
    var showFolderPicker by remember { mutableStateOf(false) }
    var showPermDialog by remember { mutableStateOf(false) }
    // 文件夹扫描选项（切换后重新扫描）
    var folderRecursive by remember { mutableStateOf(true) }

    LaunchedEffect(loadState) {
        val msg = when (val s = loadState) {
            is LoadState.Failed -> "加载失败：${s.message}"
            else -> null
        }
        if (msg != null) snackbar.showSnackbar(msg)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AssetStudio") },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "菜单")
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("设置") },
                            leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                showSettings = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("关于") },
                            leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                showAbout = true
                            }
                        )
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // ---------- 打开文件卡片 ----------
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "打开 Unity 资源文件",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        "支持 AssetBundle（.bundle/.unity3d）、序列化文件（.assets）、\n" +
                            "以及 gzip/brotli/zip 容器。可多选：.assets 与对应 .resS 一起选择。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                if (storageGranted) {
                                    showFilePicker = true
                                } else {
                                    showPermDialog = true
                                }
                            },
                            enabled = loadState !is LoadState.Loading,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("选择文件")
                        }
                        OutlinedButton(
                            onClick = {
                                if (storageGranted) {
                                    showFolderPicker = true
                                } else {
                                    showPermDialog = true
                                }
                            },
                            enabled = loadState !is LoadState.Loading,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("加载文件夹")
                        }
                    }
                }
            }

            // ---------- Unity 版本（可选） ----------
            OutlinedTextField(
                value = versionInput,
                onValueChange = { versionInput = it },
                label = { Text("Unity 版本（可选，如 2019.4.1f1）") },
                placeholder = { Text("版本号被剥离的文件需要填写") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // ---------- 加载状态 ----------
            when (val s = loadState) {
                is LoadState.Loading -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                Text(
                                    s.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2
                                )
                            }
                            // 批量加载：确定性进度条（当前/总数）
                            if (s.total > 0) {
                                LinearProgressIndicator(
                                    progress = { s.progress ?: 0f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            // 批量加载允许中途取消（保留已加载部分）
                            if (s.cancellable) {
                                TextButton(onClick = { viewModel.requestCancelLoad() }) {
                                    Text("取消（保留已加载部分）")
                                }
                            }
                        }
                    }
                }
                is LoadState.Loaded -> {
                    // v1.10.0：主页只有一个概念——「一个资产库」。文件数/资产数/磁盘暂存数，
                    // 批次、驻留、释放等内存概念全部退到幕后，用户无感
                    LoadedSummaryCard(
                        fileName = fileName,
                        fileCount = s.fileCount,
                        assets = assets,
                        errors = errors,
                        lazyCount = assets.count { it.isLazy },
                        onOpenList = { viewModel.openUnifiedList { onOpenList() } },
                        onClearAll = {
                            viewModel.consumeFolderSummary()
                            viewModel.clearAllSessions()
                        }
                    )
                    // 文件夹批量加载汇总（成功/跳过/失败明细）
                    if (folderSummary != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Text(
                                folderSummary!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    // 剩余文件提示卡：极少数超大文件内存一次装不下时，一键自动继续
                    pendingRemaining?.let { pr ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "还有 ${pr.files.size} 个文件未装完",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Text(
                                    "这些文件较大，手机内存一次装不下（防闪退保护自动暂停）。" +
                                        "已列出的 ${assets.size} 个资产不受影响；点击下方按钮自动继续装载剩余文件，无需反复操作。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Button(
                                    onClick = { viewModel.continueBatchLoad() },
                                    enabled = loadState !is LoadState.Loading,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.size(8.dp))
                                    Text("自动装完剩余 ${pr.files.size} 个文件")
                                }
                            }
                        }
                    }
                    if (decryptNote != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer
                            )
                        ) {
                            Text(
                                "已自动解密：$decryptNote",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
                is LoadState.Failed -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "加载失败",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                s.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                "若文件为已知密钥加密的 bundle，可尝试手动解密。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                            )
                            OutlinedButton(onClick = { showDecryptDialog = true }) {
                                Text("尝试手动解密")
                            }
                        }
                    }
                    // v1.10.2：加载失败不再顶掉已加载内容——之前失败分支只显示错误卡，
                    // 用户已加载的资产库“凭空消失”，误以为内容丢了/新文件没显示。
                    // 已有内容时错误卡下方照常显示汇总卡（可继续查看/导出）
                    if (assets.isNotEmpty()) {
                        LoadedSummaryCard(
                            fileName = fileName,
                            fileCount = viewModel.sessions.collectAsState().value.sumOf { it.fileCount },
                            assets = assets,
                            errors = errors,
                            lazyCount = assets.count { it.isLazy },
                            onOpenList = { viewModel.openUnifiedList { onOpenList() } },
                            onClearAll = {
                                viewModel.consumeFolderSummary()
                                viewModel.clearAllSessions()
                            }
                        )
                    }
                }
                LoadState.Idle -> {}
            }

            // v1.10.0：批次管理卡已删除——批次是纯内部实现（自动分装/自动腾内存/
            // 自动按需装载），用户界面上不再出现任何批次概念；资产查看唯一入口
            // = 上方汇总卡「查看资产」，清空 = 汇总卡右上角按钮

            // ---------- 手动解密对话框 ----------
            if (showDecryptDialog) {
                ManualDecryptDialog(
                    onDismiss = { showDecryptDialog = false },
                    onConfirm = { keyHex, mode, skipPrefix ->
                        showDecryptDialog = false
                        viewModel.reloadWithDecrypt(
                            context,
                            MainViewModel.ManualDecryptSpec(keyHex, mode, skipPrefix)
                        )
                    }
                )
            }

            // ---------- 应用内文件选择器（多选，加载 bundle/.assets/.resS） ----------
            if (showFilePicker) {
                FilePickerDialog(
                    allowMultiple = true,
                    onDismiss = { showFilePicker = false },
                    onConfirm = { files ->
                        showFilePicker = false
                        viewModel.specifyUnityVersion = versionInput.trim()
                        viewModel.manager.specifyUnityVersion = versionInput.trim().ifEmpty { null }
                        viewModel.consumeFolderSummary()
                        viewModel.loadFiles(files)
                    }
                )
            }

            // ---------- 文件夹选择器（选目录 → 扫描 → 确认后批量加载） ----------
            if (showFolderPicker) {
                FilePickerDialog(
                    pickDirectory = true,
                    onDismiss = { showFolderPicker = false },
                    onConfirm = { dirs ->
                        showFolderPicker = false
                        val dir = dirs.firstOrNull() ?: return@FilePickerDialog
                        viewModel.scanFolder(dir, folderRecursive)
                    }
                )
            }

            // ---------- 文件夹扫描确认对话框 ----------
            val scan = folderScan
            if (scan != null) {
                FolderLoadConfirmDialog(
                    scan = scan,
                    recursive = folderRecursive,
                    onRecursiveChange = { checked ->
                        folderRecursive = checked
                        // 切换后重扫（含/不含子文件夹的清单不同）
                        viewModel.scanFolder(scan.folder, checked)
                    },
                    onDismiss = { viewModel.consumeFolderScan() },
                    onConfirm = {
                        viewModel.consumeFolderScan()
                        viewModel.specifyUnityVersion = versionInput.trim()
                        viewModel.manager.specifyUnityVersion = versionInput.trim().ifEmpty { null }
                        viewModel.consumeFolderSummary()
                        viewModel.loadFolder(scan)
                    }
                )
            }

            // ---------- 存储权限说明 ----------
            if (showPermDialog) {
                AlertDialog(
                    onDismissRequest = { showPermDialog = false },
                    title = { Text("需要存储权限") },
                    text = {
                        Text(
                            "浏览与读取手机存储中的文件需要「所有文件访问」权限。\n\n" +
                                "点击「去授权」跳转系统设置页，找到本应用并开启" +
                                "\"允许管理所有文件\"后返回即可。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showPermDialog = false
                            requestStorage()
                        }) { Text("去授权") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showPermDialog = false }) { Text("取消") }
                    }
                )
            }

            // ---------- 设置对话框（MCP 服务器在此管理） ----------
            if (showSettings) {
                SettingsDialog(
                    mcpStatus = mcpStatus,
                    mcpStarting = mcpStarting,
                    onDismiss = { showSettings = false },
                    onStartMcp = { viewModel.startMcp(context) },
                    onStopMcp = { viewModel.stopMcp() }
                )
            }

            // ---------- 关于对话框 ----------
            if (showAbout) {
                AboutDialog(onDismiss = { showAbout = false })
            }

            // ---------- 功能说明 ----------
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("主要功能", style = MaterialTheme.typography.titleMedium)
                    FeatureRow(Icons.Filled.Inventory2, "资产浏览", "解析 bundle 内全部资产，按类型筛选、搜索；长按分类标签可置顶排序")
                    FeatureRow(Icons.Filled.Image, "贴图预览", "DXT/BC/ETC/ASTC/PVRTC 等格式硬解预览")
                    FeatureRow(Icons.Filled.SwapHoriz, "贴图 / 模型替换", "PNG 替换贴图、OBJ 替换模型，重打包保存")
                    FeatureRow(Icons.Filled.ViewInAr, "渲染信息", "材质 / 着色器结构化文本查看")
                    FeatureRow(Icons.Filled.Rule, "资产导出", "PNG / OBJ / WAV / 文本 / TypeTree 转储")
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/*
 * 已加载汇总卡（v1.10.0「无感加载」）：用户视角只有一个资产库——
 * 标题 = 文件数 · 资产数；内存装不下的部分显示为「暂存磁盘」一行轻提示。
 * 批次/驻留/释放等实现细节全部隐形；右上角提供一键清空。
 */
@Composable
private fun LoadedSummaryCard(
    fileName: String,
    fileCount: Int,
    assets: List<AssetItem>,
    errors: List<String>,
    lazyCount: Int,
    onOpenList: () -> Unit,
    onClearAll: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    fileName.ifEmpty { "已加载" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onClearAll) {
                    Text("清空")
                }
            }
            if (lazyCount > 0) {
                Text(
                    "其中 $lazyCount 项暂存磁盘（内存一次装不下全部）：列表、搜索、导出均不受影响，" +
                        "点开某项时自动从磁盘载入（约 1 秒）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            InfoRow("序列化文件", "$fileCount 个")
            InfoRow("资产总数", "${assets.size} 个")
            val textures = assets.count { it.type.value == 28 }
            val sprites = assets.count { it.type.value == 213 || it.type.value == 68 }
            val audios = assets.count { it.type.value == 83 }
            val texts = assets.count { it.type.value == 49 }
            InfoRow("贴图 / 精灵", "$textures / $sprites")
            InfoRow("音频 / 文本", "$audios / $texts")
            if (errors.isNotEmpty()) {
                InfoRow("解析警告", "${errors.size} 条（部分对象可能不可用）")
            }
            // 唯一入口：全部资产的统一列表
            Button(onClick = onOpenList, modifier = Modifier.fillMaxWidth()) {
                Text("查看资产（${assets.size} 项）")
            }
        }
    }
}

/*
 * v1.10.0：BatchListCard / BatchCardItem 已删除。
 * 批次退化为 AssetsManager 内部的分装单位（自动分装、自动腾内存、索引常驻、
 * 点开自动按需装载），不再有任何用户可见的批次界面。
 */

@Composable
private fun FeatureRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, desc: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/*
 * 设置对话框：MCP 服务器（AI 助手接入）的启动 / 停止与状态展示。
 * mcpStatus 仅在服务器真正运行时非空：null → 显示说明 + 「启动服务器」；
 * 非 null → 显示运行状态（含连接地址）+ 「停止服务器」。启动中禁用按钮防重复点击。
 */
@Composable
private fun SettingsDialog(
    mcpStatus: String?,
    mcpStarting: Boolean,
    onDismiss: () -> Unit,
    onStartMcp: () -> Unit,
    onStopMcp: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("AI 助手接入（MCP）", style = MaterialTheme.typography.titleSmall)
                if (mcpStatus == null) {
                    Text(
                        "启动内置 MCP 服务器后，AI 助手可通过局域网查询 / 导出" +
                            "当前已加载的资产（需与手机连接同一 Wi-Fi）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (mcpStarting) {
                        Text(
                            "正在启动…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    Text(
                        mcpStatus,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            if (mcpStatus == null) {
                TextButton(
                    onClick = onStartMcp,
                    enabled = !mcpStarting
                ) { Text(if (mcpStarting) "启动中…" else "启动服务器") }
            } else {
                TextButton(onClick = onStopMcp) { Text("停止服务器") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/*
 * 关于对话框：版本与功能简介。
 */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关于 AssetStudio") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("版本 v1.10.2", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Android 端 Unity 资产查看 / 导出 / 替换工具，支持 AssetBundle、" +
                        "序列化文件与 gzip/brotli/zip 容器，常见加密 bundle 自动探测解密。",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "功能：整文件夹无感加载（内存装不下自动转磁盘暂存、点开秒载），" +
                        "贴图 / 精灵 / 网格预览，PNG / OBJ / WAV / 文本导出，" +
                        "PNG 替换贴图、OBJ 替换模型并重打包，材质与着色器结构化查看，" +
                        "内置 MCP 服务器供 AI 助手远程查询导出。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "作者：醉莫",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("确定") }
        }
    )
}

/*
 * 文件夹加载确认对话框：展示扫描结果与风险提示，用户确认后批量加载。
 *
 * 防崩溃信息透明化：
 * - 待加载文件数 / 总大小 / 内存预算（超了会在加载时逐个跳过大文件）
 * - 单文件超限、数量截断等警示
 * - "包含子文件夹"开关（切换即重扫）
 */
@Composable
private fun FolderLoadConfirmDialog(
    scan: com.assetstudio.mobile.FolderScanResult,
    recursive: Boolean,
    onRecursiveChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val memCap = com.assetstudio.mobile.MainViewModel.folderMemCapBytes()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加载整个文件夹") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    scan.folder.absolutePath,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2
                )
                if (scan.scanError != null) {
                    Text(
                        scan.scanError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (scan.files.isEmpty()) {
                    Text(
                        "未找到可加载的资源文件（bundle / unity3d / assets / zip / resS …）。\n" +
                            "可尝试开启「包含子文件夹」。",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    InfoRow("待加载文件", "${scan.files.size} 个")
                    InfoRow("总大小", MainViewModel.formatBytes(scan.totalBytes))
                    if (scan.files.size < scan.scannedCount) {
                        InfoRow("目录内文件总数", "${scan.scannedCount} 个（已自动过滤非资源文件）")
                    }
                    InfoRow("内存预算", MainViewModel.formatBytes(memCap) + "（超出部分自动跳过大文件）")
                    if (scan.skippedTooBig.isNotEmpty()) {
                        Text(
                            "以下 ${scan.skippedTooBig.size} 个文件超过单文件上限（768MB）将被跳过：\n" +
                                scan.skippedTooBig.take(3).joinToString("、") +
                                if (scan.skippedTooBig.size > 3) " 等" else "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (scan.truncatedCount > 0) {
                        Text(
                            "文件数超过 ${MainViewModel.MAX_FOLDER_FILES}，将只加载较小的 ${scan.files.size} 个。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else if (scan.overBudgetHint(memCap)) {
                        Text(
                            "总大小超过内存预算：加载会自动在预算内进行，超出的大文件将被跳过。\n" +
                                "如需全部加载，请分批选择子文件夹。",
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        // 没有截断、没有超限：明确告知全部加载
                        Text(
                            "${scan.files.size} 个文件全部在内存预算内，将全部加载。",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Switch(checked = recursive, onCheckedChange = onRecursiveChange)
                        Text("包含子文件夹", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "加载过程逐个进行、可随时取消；单个文件损坏或过大只跳过该文件，不影响其余。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = scan.files.isNotEmpty()
            ) { Text("开始加载") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/*
 * 手动解密对话框：已知密钥的加密 bundle 兜底入口。
 * - XOR：循环多字节密钥（hex），也可只填 2 位十六进制做单字节
 * - AES-ECB / AES-CBC：密钥长度自动识别 128/192/256（hex 输入 32/48/64 位）
 * - 前缀偏移：先跳过文件头部若干字节再解密（部分游戏仅加密头部之后的数据）
 */
@Composable
private fun ManualDecryptDialog(
    onDismiss: () -> Unit,
    onConfirm: (keyHex: String, mode: EncryptedBundleDecoder.DecryptMode, skipPrefix: Int) -> Unit
) {
    var keyInput by remember { mutableStateOf("") }
    var modeIndex by remember { mutableStateOf(0) }
    var prefixInput by remember { mutableStateOf("0") }
    var keyError by remember { mutableStateOf<String?>(null) }
    val modes = listOf("XOR", "AES-ECB", "AES-CBC")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动解密加载") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "自动探测无法识别该文件时，若你知道游戏使用的加密密钥，" +
                        "可在此解密后重新加载。密钥用十六进制表示（如 0d0a1b2c 或多字节循环密钥）。",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = {
                        keyInput = it
                        keyError = null
                    },
                    label = { Text("密钥（hex）") },
                    placeholder = { Text("例：1a2b3c4d 或 1a") },
                    isError = keyError != null,
                    supportingText = keyError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    modes.forEachIndexed { i, label ->
                        FilterChip(
                            selected = modeIndex == i,
                            onClick = { modeIndex = i },
                            label = { Text(label) }
                        )
                    }
                }
                OutlinedTextField(
                    value = prefixInput,
                    onValueChange = { prefixInput = it.filter { c -> c.isDigit() } },
                    label = { Text("前缀偏移（跳过头部字节数）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Text(
                    "提示：AES 密钥须为 16/24/32 字节（hex 长度 32/48/64）；" +
                        "CBC 模式 IV 默认取密文前 16 字节。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val hex = keyInput.trim().removePrefix("0x")
                // 校验
                try {
                    EncryptedBundleDecoder.hexToBytes(hex)
                    val prefix = prefixInput.toIntOrNull() ?: 0
                    onConfirm(hex, EncryptedBundleDecoder.DecryptMode.entries[modeIndex], prefix)
                } catch (e: Exception) {
                    keyError = "密钥格式错误：${e.message}"
                }
            }) { Text("解密并加载") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

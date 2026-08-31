package com.assetstudio.mobile.ui.detail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assetstudio.mobile.AssetItem
import com.assetstudio.mobile.DemandState
import com.assetstudio.mobile.MainViewModel
import com.assetstudio.mobile.core.classes.AudioClip
import com.assetstudio.mobile.core.classes.Font
import com.assetstudio.mobile.core.classes.Material
import com.assetstudio.mobile.core.classes.Mesh
import com.assetstudio.mobile.core.classes.MeshFilter
import com.assetstudio.mobile.core.classes.MonoBehaviour
import com.assetstudio.mobile.core.classes.Shader
import com.assetstudio.mobile.core.classes.SkinnedMeshRenderer
import com.assetstudio.mobile.core.classes.Sprite
import com.assetstudio.mobile.core.classes.TextAsset
import com.assetstudio.mobile.core.classes.Texture2D
import com.assetstudio.mobile.core.classes.VideoClip
import com.assetstudio.mobile.core.io.EndianBinaryReader
import com.assetstudio.mobile.core.serialized.TypeTree
import com.assetstudio.mobile.core.serialized.TypeTreeHelper
import com.assetstudio.mobile.export.AssetExporter
import com.assetstudio.mobile.render.RenderDumper
import com.assetstudio.mobile.ui.browser.FilePickerDialog
import com.assetstudio.mobile.ui.browser.FileSaveDialog
import com.assetstudio.mobile.ui.browser.rememberStoragePermission
import com.assetstudio.mobile.ui.components.InfoRow
import com.assetstudio.mobile.ui.components.LoadingOverlay
import com.assetstudio.mobile.ui.components.MeshPreview
import com.assetstudio.mobile.ui.components.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetDetailScreen(
    viewModel: MainViewModel,
    assetId: String,
    onBack: () -> Unit,
    /** 点击关联资产跳转其详情页（如 SkinnedMeshRenderer 引用的 Mesh） */
    onOpenAsset: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val assets by viewModel.assets.collectAsState()
    val item = remember(assetId, assets) { assets.firstOrNull { it.id == assetId } }
    val pendingSave by viewModel.pendingSave.collectAsState()
    // v1.10.0：按需装载状态——懒条目（所在文件未驻留内存）点开时自动从磁盘载入
    val demandState by viewModel.demandState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    // v1.10.0 核心补全：懒条目进入详情页即自动装载（只读它所在的那一个文件）。
    // 装载成功后 MainViewModel 会回填统一列表，item.obj 变为非空并自动重组出预览
    LaunchedEffect(assetId, item?.isLazy) {
        if (item?.isLazy == true) {
            viewModel.requestAssetLive(assetId)
        }
    }

    // 替换确认对话框状态
    var pendingBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var keepFormat by remember { mutableStateOf(true) }
    // 待确认的模型替换内容：文本 + IO 线程统计好的摘要（主线程不做任何全文扫描）
    var pendingObj by remember { mutableStateOf<PendingObj?>(null) }

    val texture = item?.obj as? Texture2D
    val meshObj = item?.obj as? Mesh

    // ---------- 渲染器类组件（SkinnedMeshRenderer / MeshFilter）引用的网格 ----------
    // 这些组件本身不含几何数据，几何在 m_Mesh PPtr 指向的 Mesh 对象里：
    // - 预览：解引用后复用 Mesh 预览器
    // - 替换：替换目标就是这个 Mesh（pathID 重写走 Mesh 对象所在文件）
    val referencedMesh: Mesh? = remember(item) {
        when (val o = item?.obj) {
            is SkinnedMeshRenderer -> if (!o.m_Mesh.isNull) o.m_Mesh.tryGet() else null
            is MeshFilter -> if (!o.m_Mesh.isNull) o.m_Mesh.tryGet() else null
            else -> null
        }
    }

    // 模型替换目标：直接是 Mesh，或是渲染器组件引用的 Mesh
    val replaceTargetMesh = meshObj ?: referencedMesh

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            message = null
        }
    }

    // ---------- 应用内文件选择（替代 SAF OpenDocument） ----------
    /** 当前选择目标：替换贴图（选图片）/ 替换模型（选 OBJ） */
    var pickTarget by remember { mutableStateOf<PickTarget?>(null) }

    // ---------- 应用内保存（替代 SAF：SAF 会按 MIME 强制改后缀 .txt/.bin） ----------
    val (storageGranted, requestStorage) = rememberStoragePermission()

    /** 当前等待保存的请求：导出当前资产 / 另存替换结果 */
    var saveRequest by remember { mutableStateOf<SaveRequest?>(null) }
    var showPermDialog by remember { mutableStateOf(false) }

    if (item == null) {
        Scaffold(topBar = { DetailTopBar("资产", onBack) }) { padding ->
            Box(Modifier.padding(padding)) {
                Text("资产不存在（可能已重新加载文件）", Modifier.padding(24.dp))
            }
        }
        return
    }

    Scaffold(
        topBar = { DetailTopBar(item.name.ifEmpty { item.type.name }, onBack) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(Modifier.height(4.dp))

                // ---------- 预览区 ----------
                // v1.10.0：懒条目（obj=null）先显示装载占位卡——按需装载由上方
                // LaunchedEffect 自动触发，成功回填后此处自动重组为真实预览
                if (item.obj == null) {
                    when (val ds = demandState) {
                        is DemandState.Failed -> Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("从磁盘载入失败", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    ds.reason.ifEmpty { "该资产所在的文件无法重新读取（可能已被移动或删除）" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedButton(onClick = { viewModel.requestAssetLive(assetId) }) {
                                    Text("重试")
                                }
                            }
                        }
                        else -> Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                Column {
                                    Text("正在从磁盘载入「${item.name}」…", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "只载入该项所在的这一个文件，通常 1 秒内完成",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                } else when (val obj = item.obj) {
                    is Texture2D -> TexturePreview(viewModel, obj)
                    is Sprite -> SpritePreview(viewModel, obj)
                    is Mesh -> MeshPreview(obj)
                    is TextAsset -> TextPreview(obj)
                    is MonoBehaviour -> MonoPreview(obj)
                    is Material -> RenderDumpPreview("材质信息") { RenderDumper.dumpMaterial(obj) }
                    is Shader -> RenderDumpPreview("着色器信息") { RenderDumper.dumpShader(obj) }
                    // 渲染器组件：预览其引用的网格（几何数据在 Mesh 对象里，不在组件内）
                    is SkinnedMeshRenderer, is MeshFilter -> {
                        if (referencedMesh != null) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                MeshPreview(referencedMesh)
                                Text(
                                    "网格来自 ${item.type.name} 引用的 Mesh" +
                                        "（${referencedMesh.m_VertexCount} 顶点 / ${referencedMesh.m_Indices.size / 3} 三角形）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            // 引用为空或指向未加载的外部文件（如剥离的依赖包）
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("资产预览", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${item.type.name} 未引用可加载的网格" +
                                            "（引用为空，或指向未加载的外部依赖文件），\n可导出原始数据或 TypeTree 转储。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    else -> GenericPreview(item)
                }

                // ---------- 信息卡 ----------
                // SelectionContainer：信息卡内所有文本支持长按自由选择复制
                //（长按出现系统选择手柄，可跨行拖动选择任意范围文字，点"复制"即可）
                Card(modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("资产信息", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(6.dp))
                            InfoRow("类型", item.type.name)
                            InfoRow("PathID", item.pathID.toString())
                            InfoRow("大小", formatBytes(item.byteSize))
                            InfoRow("所在文件", item.fileName)
                            if (item.containerPath.isNotEmpty()) {
                                InfoRow("容器路径", item.containerPath.substringAfterLast('/'))
                            }
                            if (item.obj is Texture2D) {
                                val t = item.obj as Texture2D
                                InfoRow("尺寸", "${t.m_Width} x ${t.m_Height}")
                                InfoRow("格式", t.m_TextureFormat.name)
                                InfoRow("Mip 级数", t.m_MipCount.toString())
                                val streamed = t.m_StreamData
                                if (streamed != null && streamed.path.isNotEmpty()) {
                                    InfoRow("数据源", "流式 (${streamed.path.substringAfterLast('/')})")
                                } else {
                                    InfoRow("数据源", "内联")
                                }
                            }
                            if (item.obj is Sprite) {
                                val s = item.obj as Sprite
                                val info = try { s.getTextureInfo() } catch (e: Exception) { null }
                                if (info != null) {
                                    InfoRow(
                                        "纹理区域",
                                        "%.0f x %.0f".format(info.textureRect.width, info.textureRect.height)
                                    )
                                }
                            }
                            if (item.obj is Mesh) {
                                val m = item.obj as Mesh
                                InfoRow("子网格", m.m_SubMeshes.size.toString())
                                InfoRow("顶点数", m.m_VertexCount.toString())
                                InfoRow("三角形", (m.m_Indices.size / 3).toString())
                                InfoRow("压缩网格", if (m.m_CompressedMesh != null &&
                                    (m.m_CompressedMesh!!.m_Vertices.m_NumItems > 0 ||
                                        m.m_CompressedMesh!!.m_Triangles.m_NumItems > 0)
                                ) "是（已解压）" else "否")
                            }
                            if (item.obj is SkinnedMeshRenderer) {
                                val smr = item.obj as SkinnedMeshRenderer
                                InfoRow("骨骼数量", smr.m_Bones.size.toString())
                                InfoRow("材质槽位", smr.m_Materials.size.toString())
                                smr.m_BlendShapeWeights?.let {
                                    InfoRow("混合形状权重", it.size.toString())
                                }
                                InfoRow(
                                    "蒙皮网格",
                                    if (smr.m_Mesh.isNull) "无引用"
                                    else referencedMesh?.let { m ->
                                        "${m.m_VertexCount} 顶点 / ${m.m_SubMeshes.size} 子网格"
                                    } ?: "引用未加载（外部文件）"
                                )
                            }
                            if (item.obj is MeshFilter) {
                                val mf = item.obj as MeshFilter
                                InfoRow(
                                    "网格引用",
                                    if (mf.m_Mesh.isNull) "无引用"
                                    else referencedMesh?.let { m ->
                                        "${m.m_VertexCount} 顶点 / ${m.m_SubMeshes.size} 子网格"
                                    } ?: "引用未加载（外部文件）"
                                )
                            }
                            if (item.obj is AudioClip) {
                                val samples = try { (item.obj as AudioClip).listSamples().size } catch (e: Exception) { 0 }
                                InfoRow("音频样本", "$samples")
                            }
                        }
                    }
                }

                // ---------- 关联资产卡（渲染器组件引用的 Mesh，点击跳转详情） ----------
                referencedMesh?.let { mesh ->
                    // 关联 Mesh 是否在已加载资产列表中（跨文件引用未加载时跳转无效，只展示信息）
                    val meshAssetId = remember(mesh, assets) {
                        val id = "${mesh.assetsFile.fileName}#${mesh.m_PathID}"
                        if (assets.any { it.id == id }) id else null
                    }
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("关联资产", style = MaterialTheme.typography.titleSmall)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        mesh.m_Name ?: "Mesh #${mesh.m_PathID}",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        "${mesh.m_VertexCount} 顶点 · ${mesh.m_SubMeshes.size} 子网格 · ${mesh.m_Indices.size / 3} 三角形",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (meshAssetId != null) {
                                    TextButton(onClick = { onOpenAsset(meshAssetId) }) {
                                        Text("查看")
                                    }
                                } else {
                                    Text(
                                        "外部文件",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // ---------- 操作区 ----------
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (!storageGranted) {
                                showPermDialog = true
                                return@OutlinedButton
                            }
                            // 按钮在懒条目装载完成前已禁用（enabled = item.obj != null），此处必非空
                            val ext = AssetExporter.suggestedExtension(item.obj!!)
                            val base = item.name.ifEmpty { item.type.name }
                                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                            saveRequest = SaveRequest.Export("$base.$ext")
                        },
                        // v1.10.0：懒条目装载完成前禁用（装载自动进行，通常 1 秒内解锁）
                        enabled = item.obj != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(if (item.obj == null) "载入中…" else "导出")
                    }
                    if (texture != null) {
                        Button(
                            onClick = {
                                if (storageGranted) {
                                    pickTarget = PickTarget.Texture
                                } else {
                                    showPermDialog = true
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("替换贴图")
                        }
                    }
                    // 替换模型：Mesh 资产本身，或渲染器组件（SkinnedMeshRenderer/MeshFilter）引用的 Mesh
                    if (replaceTargetMesh != null) {
                        Button(
                            onClick = {
                                if (storageGranted) {
                                    pickTarget = PickTarget.Model
                                } else {
                                    showPermDialog = true
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(if (meshObj != null) "替换模型" else "替换网格")
                        }
                    }
                }

                // ---------- 待保存提示 ----------
                pendingSave?.let { result ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "替换完成，等待另存",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                "已重打包 ${formatBytes(result.bytes.size.toLong())}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                "提示：另存时建议保持原文件名与后缀（如 .ab/.bundle），可直接替换游戏内同名文件。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                            )
                            Button(
                                onClick = {
                                    if (!storageGranted) {
                                        showPermDialog = true
                                    } else {
                                        saveRequest = SaveRequest.Pending(result.suggestedName)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("另存为新文件")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(32.dp))
            }

            // ---------- 忙碌遮罩 ----------
            if (busy != null) {
                LoadingOverlay(busy!!)
            }
        }
    }

    // ---------- 替换模型确认对话框 ----------
    pendingObj?.let { pending ->
        if (replaceTargetMesh != null) {
            AlertDialog(
                onDismissRequest = { pendingObj = null },
                title = { Text("确认替换模型") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("原模型: ${replaceTargetMesh.m_VertexCount} 顶点 / ${replaceTargetMesh.m_SubMeshes.size} 子网格 / ${replaceTargetMesh.m_Indices.size / 3} 三角形")
                        Text("新模型: ${pending.summary}")
                        if (meshObj == null) {
                            Text(
                                "将替换 ${item.type.name} 引用的 Mesh（${replaceTargetMesh.m_Name ?: "#" + replaceTargetMesh.m_PathID}），骨骼绑定与材质槽保持不变。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            "替换将重写顶点 / 法线 / UV / 索引 / 包围盒（名称保留）。" +
                                "若缺少法线或 UV，将自动生成占位数据；三角带模型请先转换为三角形列表。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val text = pending.text
                        pendingObj = null
                        busy = "正在替换模型并重打包…"
                        viewModel.replaceMesh(replaceTargetMesh, text) { ok, msg ->
                            busy = null
                            message = msg
                        }
                    }) {
                        Text("替换")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingObj = null }) {
                        Text("取消")
                    }
                }
            )
        }
    }

    // ---------- 替换贴图确认对话框 ----------
    if (pendingBitmap != null && texture != null) {
        AlertDialog(
            onDismissRequest = { pendingBitmap = null },
            title = { Text("确认替换贴图") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("原贴图: ${texture.m_Width} x ${texture.m_Height} (${texture.m_TextureFormat.name})")
                    Text("新贴图: ${pendingBitmap!!.width} x ${pendingBitmap!!.height}")
                    if (pendingBitmap!!.width != texture.m_Width || pendingBitmap!!.height != texture.m_Height) {
                        Text(
                            "注意：尺寸与原图不同，尺寸字段将同步更新；引用该贴图的精灵裁剪区域可能偏移。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = keepFormat, onCheckedChange = { keepFormat = it })
                        Text(
                            "尽量保持原格式（不支持时回退 RGBA32）",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val bmp = pendingBitmap!!
                    pendingBitmap = null
                    busy = "正在替换并重打包…"
                    viewModel.replaceTexture(texture, bmp, keepFormat) { ok, msg ->
                        busy = null
                        message = msg
                    }
                }) {
                    Text("替换")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBitmap = null }) {
                    Text("取消")
                }
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
                    "应用内保存文件需要「所有文件访问」权限。\n\n" +
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

    // ---------- 应用内保存文件管理器 ----------
    saveRequest?.let { request ->
        FileSaveDialog(
            initialName = request.suggestedName,
            onDismiss = { saveRequest = null },
            onConfirm = { file ->
                saveRequest = null
                when (request) {
                    is SaveRequest.Export -> {
                        val target = item ?: return@FileSaveDialog
                        busy = "正在导出…"
                        viewModel.exportAssetToFile(target, file) { ok, msg ->
                            busy = null
                            message = msg
                        }
                    }
                    is SaveRequest.Pending -> {
                        busy = "正在保存…"
                        viewModel.savePendingToFile(file) { ok, msg ->
                            busy = null
                            message = msg
                        }
                    }
                }
            }
        )
    }
    // ---------- 应用内文件选择器（替换贴图选图片 / 替换模型选 OBJ） ----------
    pickTarget?.let { target ->
        FilePickerDialog(
            extensions = if (target == PickTarget.Texture) {
                setOf("png", "jpg", "jpeg", "webp", "bmp")
            } else {
                // 兼容此前被 SAF 存成 .txt 的 OBJ 导出文件
                setOf("obj", "txt")
            },
            onDismiss = { pickTarget = null },
            onConfirm = { files ->
                pickTarget = null
                val file = files.firstOrNull() ?: return@FilePickerDialog
                when (target) {
                    PickTarget.Texture -> {
                        busy = "正在读取图片…"
                        scope.launch {
                            val bmp = withContext(Dispatchers.IO) {
                                try {
                                    BitmapFactory.decodeFile(file.absolutePath)
                                } catch (e: Exception) {
                                    null
                                }
                            }
                            busy = null
                            if (bmp != null) {
                                pendingBitmap = bmp
                            } else {
                                message = "图片读取失败，请换一张图片"
                            }
                        }
                    }
                    PickTarget.Model -> {
                        busy = "正在读取模型文件…"
                        scope.launch {
                            // 读取 + 顶点/面统计全部在 IO 线程完成后才回主线程弹确认框，
                            // 主线程不做任何全文扫描（否则大文件会 ANR）
                            val result = withContext(Dispatchers.IO) {
                                try {
                                    val bytes = file.readBytes()
                                    if (bytes.size > 96 * 1024 * 1024) {
                                        return@withContext null to
                                            "文件过大（${bytes.size / 1024 / 1024} MB），请确认选择的是 OBJ 文本文件"
                                    }
                                    val text = bytes.toString(Charsets.UTF_8)
                                    var v = 0
                                    var f = 0
                                    for (line in text.lineSequence()) {
                                        if (line.startsWith("v ")) v++
                                        else if (line.startsWith("f ")) f++
                                    }
                                    PendingObj(text, "$v 顶点 / $f 面") to null
                                } catch (e: OutOfMemoryError) {
                                    null to "内存不足：文件太大，无法读入"
                                } catch (e: Exception) {
                                    null to "OBJ 文件读取失败：${e.message}"
                                }
                            }
                            busy = null
                            val (pending, err) = result
                            if (pending != null) {
                                pendingObj = pending
                            } else {
                                message = err
                            }
                        }
                    }
                }
            }
        )
    }
}

/** 文件选择目标 */
private enum class PickTarget { Texture, Model }

/** 保存请求：导出当前资产 / 另存替换重打包结果 */
private sealed interface SaveRequest {
    val suggestedName: String
    data class Export(override val suggestedName: String) : SaveRequest
    data class Pending(override val suggestedName: String) : SaveRequest
}

/** 待确认的模型替换内容：完整 OBJ 文本 + IO 线程统计好的摘要 */
private data class PendingObj(
    val text: String,
    val summary: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        }
    )
}

// ============================ 预览组件 ============================

@Composable
private fun TexturePreview(viewModel: MainViewModel, texture: Texture2D) {
    val bitmap by produceState<Bitmap?>(initialValue = null, texture) {
        value = viewModel.decodeTexture(texture)
    }
    PreviewCard(bitmap, "贴图预览")
}

@Composable
private fun SpritePreview(viewModel: MainViewModel, sprite: Sprite) {
    val bitmap by produceState<Bitmap?>(initialValue = null, sprite) {
        value = viewModel.decodeSprite(sprite)
    }
    PreviewCard(bitmap, "精灵预览")
}

@Composable
private fun PreviewCard(bitmap: Bitmap?, title: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (bitmap == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "无法预览",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "格式不支持或流数据缺失",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                AdaptiveImagePreview(bitmap)
            }
        }
    }
}

/**
 * 自适应贴图预览：小尺寸贴图等比放大到可辨识大小（原始比例不变），
 * 并提供滑杆拖拉控制等比缩放。
 *
 * 适配规则：
 * 1. 基准：完整放入（可用宽度 × 最大预览高度 340dp）——小图放大、大图缩小；
 *    可辨识下限（显示高度 ≥96dp、宽度 ≥48dp，受总高 640dp 上限约束）
 * 2. 滑杆在此基础上乘缩放系数（25% ~ 800%），切换贴图时自动重置为 100%
 * 3. 缩放后内容超出视口（480dp 高 / 卡片宽）时双向滑动查看
 * 4. 放大超过原始像素时用 FilterQuality.None（最近邻，像素块清晰）
 */
@Composable
private fun AdaptiveImagePreview(bitmap: Bitmap) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        val density = LocalDensity.current
        val w = bitmap.width
        val h = bitmap.height
        if (w > 0 && h > 0) {
            // 用户缩放系数（相对自适应基准），切换贴图时重置
            var zoom by remember(bitmap) { mutableStateOf(1f) }

            val maxWpx = constraints.maxWidth.toFloat()
            val maxHpx = with(density) { 340.dp.toPx() }
            val minHpx = with(density) { 96.dp.toPx() }
            val minWpx = with(density) { 48.dp.toPx() }
            val hardMaxHpx = with(density) { 640.dp.toPx() }

            // 自适应基准（小图等比放大 + 可辨识下限）
            var fitScale = minOf(maxWpx / w, maxHpx / h)
            fitScale = maxOf(fitScale, minOf(minHpx / h, hardMaxHpx / h))
            fitScale = maxOf(fitScale, minOf(minWpx / w, hardMaxHpx / h))

            val scale = fitScale * zoom
            val dispW = (w * scale).roundToInt()
            val dispH = (h * scale).roundToInt()
            val scrollable = dispW > constraints.maxWidth ||
                dispH > with(density) { 480.dp.toPx() }

            Column {
                // 预览视口：内容超出（宽 > 卡片 / 高 > 480dp）时双向滑动
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = with(density) { 480.dp })
                        .clipToBounds()
                        .horizontalScroll(rememberScrollState())
                        .verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = BitmapPainter(
                            bitmap.asImageBitmap(),
                            filterQuality = if (scale > 1f) FilterQuality.None else FilterQuality.Low
                        ),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.size(
                            with(density) { dispW.toDp() },
                            with(density) { dispH.toDp() }
                        )
                    )
                }
                // 缩放滑杆：拖拉控制等比缩放（相对自适应基准的百分比）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "缩放",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = zoom,
                        onValueChange = { zoom = it },
                        valueRange = 0.25f..8f,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${(zoom * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(44.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                }
                Text(
                    buildString {
                        append("$w x $h")
                        if (scale > 1.01f) append("（预览放大 ${"%.1f".format(scale)}x）")
                        if (scrollable) append("，超出部分可滑动查看")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TextPreview(textAsset: TextAsset) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("文本内容", style = MaterialTheme.typography.titleSmall)
            val content = remember(textAsset) {
                try {
                    val raw = textAsset.m_Script
                    if (raw.size > 200_000) {
                        String(raw.copyOfRange(0, 200_000), Charsets.UTF_8) + "\n…（已截断，共 ${raw.size} 字节）"
                    } else {
                        String(raw, Charsets.UTF_8)
                    }
                } catch (e: Exception) {
                    "(非 UTF-8 文本，共 ${textAsset.m_Script.size} 字节)"
                }
            }
            Text(
                content,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .height(320.dp)
                    .verticalScroll(rememberScrollState())
            )
        }
    }
}

@Composable
private fun MonoPreview(mono: MonoBehaviour) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("TypeTree 转储", style = MaterialTheme.typography.titleSmall)
            val dump by produceState<String?>(initialValue = null, mono) {
                value = withContext(Dispatchers.IO) {
                    try {
                        val typeTree = mono.reader.serializedType?.m_Type
                        if (typeTree != null) {
                            mono.reader.reset()
                            TypeTreeHelper.readTypeString(typeTree, mono.reader)
                        } else {
                            "（该对象无 TypeTree 信息）"
                        }
                    } catch (e: Exception) {
                        "转储失败: ${e.message}"
                    }
                }
            }
            Text(
                dump ?: "正在转储…",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .height(320.dp)
                    .verticalScroll(rememberScrollState())
            )
        }
    }
}

/**
 * 渲染资产（Material / Shader）文本转储预览：
 * 转储在 IO 线程执行，结果可滚动查看，支持长按选择复制。
 */
@Composable
private fun RenderDumpPreview(title: String, dump: () -> String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            val text by produceState<String?>(initialValue = null, title) {
                value = withContext(Dispatchers.IO) {
                    try {
                        dump()
                    } catch (e: Exception) {
                        "转储失败: ${e.message}"
                    }
                }
            }
            SelectionContainer {
                Text(
                    text ?: "正在解析…",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .height(360.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}

@Composable
private fun GenericPreview(item: AssetItem) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("资产预览", style = MaterialTheme.typography.titleSmall)
            Text(
                "${item.type.name} 类型暂不支持可视化预览，\n可导出原始数据或 TypeTree 转储。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

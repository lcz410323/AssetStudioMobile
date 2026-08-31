package com.assetstudio.mobile

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assetstudio.mobile.core.ClassIDType
import com.assetstudio.mobile.core.classes.Mesh
import com.assetstudio.mobile.core.classes.Sprite
import com.assetstudio.mobile.core.classes.Texture2D
import com.assetstudio.mobile.core.classes.UnityObject
import com.assetstudio.mobile.core.crypto.EncryptedBundleDecoder
import com.assetstudio.mobile.core.io.getFileName
import com.assetstudio.mobile.core.manager.AssetsManager
import com.assetstudio.mobile.core.serialized.SerializedFile
import com.assetstudio.mobile.core.texture.PixelFlip
import com.assetstudio.mobile.core.texture.TextureConverter
import com.assetstudio.mobile.export.AssetExporter
import com.assetstudio.mobile.mcp.AppMcpSource
import com.assetstudio.mobile.mcp.McpManager
import com.assetstudio.mobile.replace.AssetReplacer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * 主 ViewModel：承载 AssetsManager 与全部 UI 状态。
 *
 * 加载流程：SAF 选择文件（支持多选，.assets 与其 .resS 可一起选）
 *   → ContentResolver 读字节 → AssetsManager.loadFile → 汇总资产列表。
 *
 * 替换流程：详情页选择 PNG → AssetReplacer.replaceTexture 得到重打包后的
 *   完整容器字节 → 暂存 pendingResult → SAF 另存为新文件。
 */

/**
 * 列表/详情用的资产条目（v1.9.0：索引与对象分离）。
 *
 * [obj] 可空：null = 该资产所在批次已被驱逐出内存，条目仅作为**轻量索引**
 * （名称/类型/大小/来源文件/pathID 常驻，看列表零内存压力）；点开预览或导出时
 * 由 [MainViewModel.ensureItemLive] 从磁盘**只装这一个文件**再补上对象引用。
 */
data class AssetItem(
    val obj: UnityObject?,
    val pathID: Long,
    val name: String,
    val type: ClassIDType,
    val byteSize: Long,
    val fileName: String,
    val containerPath: String,
    /** 所属批次（v1.8.1 合并视图：跨批次同名文件的 id 去重 + 批次归属） */
    val sessionId: Long = 0L
) {
    /** id 带批次前缀：合并视图下不同批次的同名文件也各自可导航（详情页按 id 精确匹配） */
    val id: String get() = "b$sessionId:$fileName#$pathID"
    /** true = 对象未在内存（批次已驱逐），需要按需装载 */
    val isLazy: Boolean get() = obj == null
}

/** 按需装载状态（v1.9.0）：详情页对懒条目自动触发从磁盘装回单个文件 */
sealed interface DemandState {
    data object Idle : DemandState
    data class Loading(val name: String) : DemandState
    data object Ready : DemandState
    data class Failed(val name: String, val reason: String = "") : DemandState
}

sealed interface LoadState {
    data object Idle : LoadState
    data class Loading(
        val message: String,
        /** 0..1 进度；null = 不确定进度（如扫描阶段） */
        val progress: Float? = null,
        /** 批量加载：当前序号 / 总数（单文件加载为 0/0） */
        val current: Int = 0,
        val total: Int = 0,
        /** 是否允许取消（文件夹批量加载中允许，保留已加载部分） */
        val cancellable: Boolean = false
    ) : LoadState
    data class Loaded(val fileCount: Int) : LoadState
    data class Failed(val message: String) : LoadState
}

/**
 * 文件夹扫描结果（确认对话框展示 + 加载输入）。
 *
 * 文件按大小升序排列：小文件先加载可以更快看到进度，
 * 内存预算耗尽时被跳过的是大文件（价值密度最低的丢弃顺序）。
 */
data class FolderScanResult(
    val folder: java.io.File,
    val recursive: Boolean,
    /** 待加载文件（按大小升序） */
    val files: List<java.io.File>,
    val totalBytes: Long,
    /** 扫描到的文件总数（含被扩展名/魔数过滤掉的） */
    val scannedCount: Int,
    /** 单文件超过上限被剔除的文件名 */
    val skippedTooBig: List<String>,
    /** 超过数量上限被截断的数量（已在 files 中排除） */
    val truncatedCount: Int,
    /** 目录不可读等错误 */
    val scanError: String? = null
) {
    /** 估算加载后内存占用是否超过软上限（提示用户，不阻断） */
    fun overBudgetHint(capBytes: Long): Boolean = totalBytes > capBytes
}

class MainViewModel : ViewModel() {

    /**
     * 当前活跃批次的解析器（v1.8.0 批次架构）。
     *
     * 每个批次一份独立资产列表；此字段始终指向**活跃批次**的解析器，
     * 批次切换时随之更换。被驱逐的批次仅保留元数据（文件清单 + 统计），
     * 重新查看时从磁盘重解析（见 [openSession]）。
     */
    var manager: AssetsManager = AssetsManager()
        private set

    private val _loadState = MutableStateFlow<LoadState>(LoadState.Idle)
    val loadState: StateFlow<LoadState> = _loadState.asStateFlow()

    private val _assets = MutableStateFlow<List<AssetItem>>(emptyList())
    val assets: StateFlow<List<AssetItem>> = _assets.asStateFlow()

    /** 最近一次加载的顶层文件名（用于标题显示） */
    private val _loadedFileName = MutableStateFlow("")
    val loadedFileName: StateFlow<String> = _loadedFileName.asStateFlow()

    /** 加载错误（非致命，部分对象解析失败时仍可用） */
    private val _loadErrors = MutableStateFlow<List<String>>(emptyList())
    val loadErrors: StateFlow<List<String>> = _loadErrors.asStateFlow()

    /** 替换结果暂存（等待用户另存）：贴图/模型替换共用 */
    data class PendingSave(
        val bytes: ByteArray,
        val suggestedName: String
    )

    private val _pendingSave = MutableStateFlow<PendingSave?>(null)
    val pendingSave: StateFlow<PendingSave?> = _pendingSave.asStateFlow()

    /** MCP 服务器状态提示（null = 未运行；仅成功启动后才非空） */
    private val _mcpStatus = MutableStateFlow<String?>(null)
    val mcpStatus: StateFlow<String?> = _mcpStatus.asStateFlow()

    /** MCP 服务器启动中（防重复点击 + 按钮显示"启动中…"） */
    private val _mcpStarting = MutableStateFlow(false)
    val mcpStarting: StateFlow<Boolean> = _mcpStarting.asStateFlow()

    /** 全局操作提示 */
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /** 用户指定的 Unity 版本（版本被剥离的文件需要） */
    var specifyUnityVersion: String = ""

    /** 自动解密提示（加载后一次性消费，null = 未触发自动解密） */
    private val _decryptNote = MutableStateFlow<String?>(null)
    val decryptNote: StateFlow<String?> = _decryptNote.asStateFlow()

    /** 最近一次选择的文件 Uri（手动解密重试时复用） */
    private var lastUris: List<Uri> = emptyList()

    /** 最近一次通过应用内文件管理器选择的文件（File 直读路径，手动解密重试时复用） */
    private var lastFiles: List<java.io.File> = emptyList()

    /**
     * 手动解密参数（一次性）：下次 loadUris 时对文件字节先解密再解析。
     * 用于自动探测无法处理的已知密钥加密 bundle。
     */
    var manualDecryptSpec: ManualDecryptSpec? = null

    /** 手动解密参数 */
    data class ManualDecryptSpec(
        val keyHex: String,
        val mode: EncryptedBundleDecoder.DecryptMode,
        val skipPrefix: Int
    )

    fun consumeDecryptNote() {
        _decryptNote.value = null
    }

    fun consumeToast() {
        _toast.value = null
    }

    fun clearPendingResult() {
        _pendingSave.value = null
    }

    // ============================ 加载 ============================

    fun loadUris(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        lastUris = uris
        lastFiles = emptyList()
        cancelLoadRequested = false
        viewModelScope.launch {
            _loadState.value = LoadState.Loading("正在读取文件…", cancellable = true)
            val resolver = context.contentResolver
            val spec = manualDecryptSpec
            manualDecryptSpec = null // 一次性参数
            // v1.10.2：与 loadFiles 同防线——先腾堆（只驱逐可重载的 File 批次，
            // SAF 批次 canReload=false 自动跳过），再延迟构造 + 堆防线。
            withContext(Dispatchers.IO) { evictSessionsForNewBatch() }
            val result = withContext(Dispatchers.IO) {
                val m = newManager()
                var firstName: String? = null
                val errors = ArrayList<String>()
                try {
                    for (uri in uris) {
                        if (cancelLoadRequested) break
                        val name = queryDisplayName(resolver, uri) ?: "file"
                        if (firstName == null) firstName = name
                        try {
                            // 超大文件预检：Android 应用堆有限，直接整读会 OOM
                            val size = resolver.queryFileSize(uri)
                            if (size > MAX_INPUT_BYTES) {
                                errors.add(
                                    "$name 大小 ${size / 1024 / 1024}MB，" +
                                        "超过上限 ${MAX_INPUT_BYTES / 1024 / 1024}MB"
                                )
                                continue
                            }
                            var bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                                ?: throw IllegalStateException("无法读取 $name")
                            // 手动解密（用户提供的已知密钥）先行，失败直接报错
                            if (spec != null) {
                                bytes = try {
                                    EncryptedBundleDecoder.manualDecrypt(
                                        bytes,
                                        EncryptedBundleDecoder.hexToBytes(spec.keyHex),
                                        spec.mode,
                                        skipPrefix = spec.skipPrefix
                                    )
                                } catch (e: Exception) {
                                    throw IllegalStateException("手动解密失败：${e.message}")
                                }
                            }
                            m.loadFileDeferred(name, bytes)
                        } catch (e: Throwable) {
                            // 单文件失败（含 OOM）不中断，记录后继续
                            errors.add("$name: ${e.message ?: e.javaClass.simpleName}")
                        }
                    }
                    if (m.assetsFileList.isNotEmpty()) {
                        m.finishLoading { heapUsedRatioAfterGc() > HEAP_STOP_PARSING }
                        m.stripTypeTrees()
                    } else {
                        firstName = null // 一个文件都没读入 → 整体失败（沿用旧语义）
                    }
                } catch (e: Throwable) {
                    errors.add(e.message ?: e.toString())
                }
                Triple(firstName, m, (errors + m.errors.toList()).distinct())
            }
            // SAF 批次无持久 Uri 权限保证 → 不可从磁盘重载 → 永不驱逐
            applySingleLoad(
                result.first, result.second, result.third,
                canReload = false, originKey = "uris:${uris.joinToString()}"
            )
        }
    }

    /** 应用内文件管理器选择的文件（File 直读，不经 SAF） */
    fun loadFiles(files: List<java.io.File>) {
        if (files.isEmpty()) return
        lastFiles = files
        lastUris = emptyList()
        cancelLoadRequested = false
        viewModelScope.launch {
            _loadState.value = LoadState.Loading("正在读取文件…", cancellable = true)
            val spec = manualDecryptSpec
            manualDecryptSpec = null // 一次性参数
            // v1.10.2 修复（不清空加载新文件不显示）：
            // 旧实现直接在已被旧批次对象占满的堆上「整读 + 立即构造」，无任何腾挪与
            // 堆防线——先加载过文件时新文件极易 OOM/构造失败 → 主页被「加载失败」
            // 卡顶掉，看起来就是“新文件没显示出来”。这里对齐文件夹路径的防线：
            // 1) 先驱逐可重载旧批次腾堆（索引保留，列表不丢条目）；
            // 2) 延迟构造 + finishLoading 堆防线 + 剥离类型树（与批次加载完全一致）；
            // 3) 单文件失败只记错误不拖垮整批。
            withContext(Dispatchers.IO) { evictSessionsForNewBatch() }
            val result = withContext(Dispatchers.IO) {
                val m = newManager()
                var firstName: String? = null
                val errors = ArrayList<String>()
                try {
                    for (file in files) {
                        if (cancelLoadRequested) break
                        if (firstName == null) firstName = file.name
                        try {
                            val size = file.length()
                            if (size > MAX_INPUT_BYTES) {
                                errors.add(
                                    "${file.name}: 大小 ${size / 1024 / 1024}MB，" +
                                        "超过上限 ${MAX_INPUT_BYTES / 1024 / 1024}MB"
                                )
                                continue
                            }
                            var bytes = file.readBytes()
                            if (spec != null) {
                                bytes = try {
                                    EncryptedBundleDecoder.manualDecrypt(
                                        bytes,
                                        EncryptedBundleDecoder.hexToBytes(spec.keyHex),
                                        spec.mode,
                                        skipPrefix = spec.skipPrefix
                                    )
                                } catch (e: Exception) {
                                    throw IllegalStateException("手动解密失败：${e.message}")
                                }
                            }
                            m.loadFileDeferred(file.name, bytes)
                        } catch (e: Throwable) {
                            // 单文件失败（含 OOM）不中断，记录后继续
                            errors.add("${file.name}: ${e.message ?: e.javaClass.simpleName}")
                        }
                    }
                    if (m.assetsFileList.isNotEmpty()) {
                        m.finishLoading { heapUsedRatioAfterGc() > HEAP_STOP_PARSING }
                        m.stripTypeTrees()
                    } else {
                        firstName = null // 一个文件都没读入 → 整体失败（沿用旧语义）
                    }
                } catch (e: Throwable) {
                    errors.add(e.message ?: e.toString())
                }
                Triple(firstName, m, (errors + m.errors.toList()).distinct())
            }
            applySingleLoad(
                result.first, result.second, result.third,
                canReload = true, originKey = "files:${files.joinToString { it.path }}"
            )
        }
    }

    // ============================ 文件夹批量加载 ============================

    /** 扫描结果（null = 未在文件夹流程中）；HomeScreen 确认对话框观察它 */
    private val _folderScan = MutableStateFlow<FolderScanResult?>(null)
    val folderScan: StateFlow<FolderScanResult?> = _folderScan.asStateFlow()

    /** 文件夹加载完成后的汇总（成功/跳过/失败明细），Loaded 状态卡下方展示 */
    private val _folderSummary = MutableStateFlow<String?>(null)
    val folderSummary: StateFlow<String?> = _folderSummary.asStateFlow()

    /** 文件夹未加载完的剩余文件（内存安全线停止/取消），提示卡展示并可继续加载 */
    private val _pendingRemainingFiles = MutableStateFlow<PendingRemainingFiles?>(null)
    val pendingRemainingFiles: StateFlow<PendingRemainingFiles?> = _pendingRemainingFiles.asStateFlow()

    // ============================ 批次（v1.8.0） ============================

    /**
     * 一个可独立查看的资产列表（v1.8.0 批次架构；v1.9.0 索引化）。
     *
     * 文件夹装不下的文件自动链式装入下一批次；单次文件加载也登记为批次。
     * v1.9.0 起每个批次持有**轻量资产索引**（[index]）——批次被驱逐出内存后
     * 索引仍常驻：统一列表永远显示全部批次的全部资产，重对象按需装载。
     */
    data class BatchSession(
        val id: Long,
        /** 展示名：「文件夹名」批次 N / 单次加载的文件名 */
        val title: String,
        /** 构成本批次的文件（重新解析用；SAF 批次为空 → 不可重载） */
        val files: List<java.io.File>,
        /** 成功加载的序列化文件数 */
        val fileCount: Int,
        val assetCount: Int,
        val textureCount: Int,
        val spriteCount: Int,
        val audioCount: Int,
        val textCount: Int,
        /** 解析警告条数 */
        val errorCount: Int,
        /** 加载完成时的驻留字节（估算） */
        val retainedBytes: Long,
        /** 批次明细（跳过/失败等），卡片下方展示 */
        val summary: String,
        /** 对象是否驻留内存（false = 已驱逐，索引仍在，按需装载） */
        val live: Boolean,
        /** 可否从磁盘重载（SAF Uri 批次不可 → 永不驱逐） */
        val canReload: Boolean,
        /** 同源标识：重复加载同一组文件时替换旧批次 */
        val originKey: String,
        /**
         * 轻量资产索引（v1.9.0）：加载时生成；驱逐时对象引用清空、条目保留。
         * 统一列表 = 全部批次索引的并集——看列表与内存无关。
         */
        val index: List<AssetItem> = emptyList()
    )

    private val _sessions = MutableStateFlow<List<BatchSession>>(emptyList())
    val sessions: StateFlow<List<BatchSession>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow<Long?>(null)
    val activeSessionId: StateFlow<Long?> = _activeSessionId.asStateFlow()

    /** v1.9.0 起资产列表恒为**全部批次的统一视图**（此属性保留兼容旧 UI 判断） */
    private val _mergedView = MutableStateFlow(false)
    val mergedView: StateFlow<Boolean> = _mergedView.asStateFlow()

    /** 按需装载状态（v1.9.0）：详情页对懒条目自动触发，UI 显示装载进度 */
    private val _demandState = MutableStateFlow<DemandState>(DemandState.Idle)
    val demandState: StateFlow<DemandState> = _demandState.asStateFlow()

    /**
     * 按需装载（v1.9.0）：
     * - [demandManager]：最近一次从磁盘装回的**单文件级**解析器（容量 1，
     *   装新文件前丢弃旧的并 GC，内存上限 ≈ 一个文件的解析结果）；
     * - [demandAttach]：懒条目 id → 已装回对象的映射（统一列表重建时回填，
     *   丢弃按需解析器时一并清空）。
     */
    private var demandManager: AssetsManager? = null
    private var demandManagerPath: String? = null
    private val demandAttach = HashMap<String, UnityObject>()

    /** 丢弃按需装载的单文件解析器（腾内存；条目回到懒状态，可再次按需装载） */
    private fun dropDemandManager() {
        demandManager = null
        demandManagerPath = null
        demandAttach.clear()
    }

    /** 各批次的解析器（仅驻留内存的批次存在） */
    private val sessionManagers = HashMap<Long, AssetsManager>()
    private var nextSessionId = 1L

    /** 批量加载取消请求（Volatile：IO 协程写、主线程读都需要立即可见） */
    @Volatile private var cancelLoadRequested = false

    fun requestCancelLoad() {
        cancelLoadRequested = true
    }

    fun consumeFolderScan() {
        _folderScan.value = null
    }

    /**
     * 扫描文件夹，产出待加载清单（IO 线程执行）。
     *
     * 筛选规则（防止把无关文件全塞进解析器）：
     * - 扩展名白名单直接命中（bundle/unity3d/assetbundle/assets/zip/…）
     * - 无扩展名或非常见媒体/文档扩展 → 读文件头 16 字节做魔数嗅探
     *   （UnityFS/UnityWeb/UnityRaw/UnityArchive/KH 加密头/gzip）
     * - 单文件超过 [MAX_INPUT_BYTES] 记入 skippedTooBig
     * - 数量超过 [MAX_FOLDER_FILES] 截断（保留较小的）
     */
    fun scanFolder(folder: java.io.File, recursive: Boolean) {
        viewModelScope.launch {
            _loadState.value = LoadState.Loading("正在扫描 ${folder.name} …")
            val result = withContext(Dispatchers.IO) {
                try {
                    scanFolderInternal(folder, recursive)
                } catch (e: Throwable) {
                    FolderScanResult(folder, recursive, emptyList(), 0, 0, emptyList(), 0,
                        scanError = e.message ?: e.toString())
                }
            }
            _loadState.value = LoadState.Idle
            _folderScan.value = result
        }
    }

    private fun scanFolderInternal(folder: java.io.File, recursive: Boolean): FolderScanResult {
        val candidates = ArrayList<Pair<java.io.File, Long>>() // file -> size
        val tooBig = ArrayList<String>()
        var scanned = 0
        var unreadableDir = false

        fun walk(dir: java.io.File) {
            val listed = dir.listFiles() ?: run { unreadableDir = true; return }
            for (f in listed) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    if (recursive) walk(f)
                } else if (f.isFile) {
                    scanned++
                    val size = f.length()
                    if (size > MAX_INPUT_BYTES) {
                        tooBig.add(f.name)
                        continue
                    }
                    if (size == 0L) continue
                    if (isLoadableFile(f)) candidates.add(f to size)
                }
            }
        }
        walk(folder)

        // 小文件优先；数量超限时丢弃的是最大的那些
        candidates.sortBy { it.second }
        val truncated: Int
        val files: List<java.io.File>
        if (candidates.size > MAX_FOLDER_FILES) {
            files = candidates.take(MAX_FOLDER_FILES).map { it.first }
            truncated = candidates.size - MAX_FOLDER_FILES
        } else {
            files = candidates.map { it.first }
            truncated = 0
        }
        val total = files.sumOf { it.length() }
        return FolderScanResult(
            folder = folder,
            recursive = recursive,
            files = files,
            totalBytes = total,
            scannedCount = scanned,
            skippedTooBig = tooBig,
            truncatedCount = truncated,
            scanError = if (unreadableDir) "部分子目录无法读取（权限受限），已跳过" else null
        )
    }

    /** 扩展名白名单：直接认定为可加载资源文件 */
    private val folderLoadExts = setOf(
        "bundle", "unity3d", "assetbundle", "assets", "ab",
        "zip", "unitypackage", "ress", "resource", "gz", "split"
    )

    /** 常见非 Unity 资源的扩展名：跳过魔数嗅探（省一次文件头 IO） */
    private val commonOtherExts = setOf(
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "heic",
        "mp3", "ogg", "wav", "flac", "aac", "m4a",
        "mp4", "avi", "mkv", "mov", "webm",
        "txt", "json", "xml", "log", "ini", "csv", "html", "md",
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "apk", "jar", "dex", "so", "ttf", "otf",
        "rar", "7z", "obj", "fbx", "glb", "gltf", "db", "sqlite"
    )

    /** 文件是否可加载：白名单扩展命中，或头部魔数命中 Unity 容器特征 */
    private fun isLoadableFile(f: java.io.File): Boolean {
        val ext = f.name.substringAfterLast('.', "").lowercase()
        if (ext in folderLoadExts) return true
        if (ext in commonOtherExts) return false
        // 无扩展名或未知扩展：嗅探文件头（无扩展名的游戏资源包很常见）
        return try {
            val head = ByteArray(16)
            java.io.FileInputStream(f).use { ins ->
                var off = 0
                while (off < head.size) {
                    val n = ins.read(head, off, head.size - off)
                    if (n < 0) break
                    off += n
                }
            }
            sniffUnityMagic(head)
        } catch (e: Exception) {
            false
        }
    }

    /** 头部字节是否像 Unity 容器（含 KH 加密包）或 gzip 压缩 */
    private fun sniffUnityMagic(head: ByteArray): Boolean {
        fun ascii(off: Int, len: Int): String =
            String(head, off, len.coerceAtMost(head.size - off), Charsets.US_ASCII)
        if (ascii(0, 7) == "UnityFS") return true
        if (ascii(0, 8) == "UnityWeb") return true
        if (ascii(0, 8) == "UnityRaw") return true
        if (ascii(0, 11) == "UnityArchive") return true
        // AB 管理器加密包：UnityKHFS / UnityKHNFS / UnityKH1FS
        val s = ascii(0, 12)
        if (s.startsWith("UnityKH")) return true
        // gzip
        if (head.size >= 2 && (head[0].toInt() and 0xFF) == 0x1F && (head[1].toInt() and 0xFF) == 0x8B) return true
        return false
    }

    /**
     * 批量加载文件夹（防崩溃核心，v1.8.0 起自动分批）：
     *
     * 1. 逐个文件加载，单文件失败（含 OOM）只记录不中断；
     * 2. 每加载一个文件后检查实际驻留内存，超过软上限即停止本批次；
     * 3. **装不下的文件自动装入下一个批次**（新批次用独立解析器、全量堆可用），
     *    链式推进直到全部文件分配完毕——每个批次都是一份独立的、可随时
     *    查看的资产列表（见 [chainLoadBatches]）；
     * 4. 全程可取消（取消后已完成批次保留，剩余文件可继续分批加载）。
     */
    fun loadFolder(scan: FolderScanResult) {
        if (scan.files.isEmpty()) {
            _loadState.value = LoadState.Failed("文件夹中没有可加载的资源文件")
            return
        }
        cancelLoadRequested = false
        lastFiles = scan.files
        lastUris = emptyList()
        _folderScan.value = null
        _pendingRemainingFiles.value = null
        viewModelScope.launch {
            chainLoadBatches(scan.files, scan.folder.name, startBatchNo = 1, resumed = false)
        }
    }

    /** 继续分批加载剩余文件（此前链式加载被取消/中断） */
    fun continueBatchLoad() {
        val pending = _pendingRemainingFiles.value ?: return
        if (pending.files.isEmpty()) {
            _pendingRemainingFiles.value = null
            return
        }
        cancelLoadRequested = false
        viewModelScope.launch {
            chainLoadBatches(pending.files, pending.folderName, pending.nextBatchNo, resumed = true)
        }
    }

    /** 单个批次的加载结果 */
    private data class BatchOutcome(
        /** 成功读入并登记的文件 */
        val loadedFiles: List<java.io.File>,
        val failures: List<String>,        // "文件名: 原因"
        val tooBig: List<String>,          // 单文件超限（复扫保护，理论上为空）
        val overBudget: List<String>,      // 因内存预算被跳过的文件
        val cancelled: Boolean,
        val heapStopped: Boolean,          // 加载阶段真实堆超安全线被停止
        /** 因内存不足未构造对象的文件数 */
        val unconstructed: Int,
        /** 剥离的类型树节点数（内存优化） */
        val strippedNodes: Int,
        /** 本批次未读取的文件（供下一批次继续） */
        val remainingFiles: List<java.io.File>
    )

    /**
     * 未加载完的剩余文件清单（HomeScreen 提示卡观察）。
     * 非空时展示「还有 N 个文件未加载」+ 分批加载按钮（装入新批次）。
     */
    data class PendingRemainingFiles(
        val files: List<java.io.File>,
        val folderName: String,
        /** 继续分批时延续批次编号 */
        val nextBatchNo: Int = 1
    )

    /**
     * 真实堆使用率（GC 后测量）。
     *
     * 说明：retainedBytes（文件字节之和）只是内存的小头——对象构造后
     * 内存会膨胀数倍，真正可靠的判据是运行时堆。偏高时先触发一次 GC
     * 复测，避免把可回收的临时垃圾误判为压力。
     */
    private fun heapUsedRatioAfterGc(): Float {
        val rt = Runtime.getRuntime()
        var used = rt.totalMemory() - rt.freeMemory()
        if (used > rt.maxMemory() / 2) {
            System.gc()
            used = rt.totalMemory() - rt.freeMemory()
        }
        return used.toFloat() / rt.maxMemory()
    }

    /**
     * 等待 GC 真正生效后的堆占比。
     *
     * `System.gc()` 只是请求：ART 的回收（并发标记 / finalize 清理）需要时间完成，
     * 紧接着读 `freeMemory()` 拿到的是回收完成前的**虚高旧值**。加载活动刚结束的
     * 瞬间误差最大（可能差出十几个百分点），这正是 v1.7.8 自动循环每轮误判
     * 「内存真满」提前退出的根因——手动点击隔几秒 GC 已生效故仍能推进。
     *
     * 这里在堆占比仍高于 [HEAP_CONTINUE_LOADING] 时反复 GC + 短暂等待 + 复测
     * （最多 [maxWaits] 次），给回收留出生效时间；仅用于轮间等低频决策点，
     * 不进入每 8 个文件的热路径（热路径仍用轻量的 [heapUsedRatioAfterGc]）。
     */
    private fun heapUsedRatioSettled(maxWaits: Int = 3, waitMs: Long = 250L): Float {
        var ratio = heapUsedRatioAfterGc()
        var waits = 0
        while (ratio > HEAP_CONTINUE_LOADING && waits < maxWaits) {
            waits++
            System.gc()
            try {
                Thread.sleep(waitMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return ratio
            }
            ratio = heapUsedRatioAfterGc()
        }
        return ratio
    }

    /**
     * 堆占比快速读数（v1.10.1，**不触发显式 GC**）。
     *
     * `System.gc()` 在 ART 上是阻塞式全量回收，伴随全局 stop-the-world 暂停
     * （大堆可达上百毫秒）——批量加载时用户在等进度无感知，但按需装载时
     * 用户正盯着详情页等预览，任何一次强制 GC 都会表现成「卡一下」。
     * 按需装载等 UI 活跃期间的热路径一律先用本读数，确有压力才升级到 GC 复测。
     */
    private fun heapUsedRatioFast(): Float {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()).toFloat() / rt.maxMemory()
    }

    /**
     * 按需装载的堆防线（v1.10.1，图二卡顿修复的一环）：
     * 快速读数先行——未超线**零 GC 开销**；超线才做一次「GC + 短等 + 复测」
     * 确认（防把可回收的临时垃圾误判为真满而误停构造）。
     */
    private fun demandHeapGuard(): Boolean =
        if (heapUsedRatioFast() > HEAP_STOP_PARSING) {
            heapUsedRatioSettled(1, 120) > HEAP_STOP_PARSING
        } else false

    /**
     * 加载一个批次（IO 线程执行）。
     *
     * 与旧版整文件夹加载的差异：解析器由调用方创建（每批次独立、全量堆可用），
     * 读满即停并把剩余文件交还调用方链式装下一批；无论是否中途停止，
     * 已读入的文件都会完成对象构造并剥离类型树——保证批次登记时资产可浏览。
     */
    private fun loadBatchInternal(
        m: AssetsManager,
        files: List<java.io.File>,
        /** 进度文案里的阶段名（如「批次 2」「重新加载」） */
        label: String,
        /** 跨批次进度：本批次开始前已完成的文件数 */
        doneBefore: Int,
        /** 跨批次进度：本次链式加载的文件总数 */
        grandTotal: Int,
        memCap: Long
    ): BatchOutcome {
        val failures = ArrayList<String>()
        val overBudget = ArrayList<String>()
        val tooBig = ArrayList<String>()
        val loaded = ArrayList<java.io.File>()
        // 本批装不下、转入下一批次的文件（新批次全量堆可用，单批预算内可装下）
        val deferred = ArrayList<java.io.File>()
        var cancelled = false
        var heapStopped = false
        var unconstructed = 0
        var strippedNodes = 0
        // 停止位置（含）：循环中断时未处理的首个文件下标；-1 = 正常跑完
        var stopIndex = -1
        val total = files.size
        for ((index, file) in files.withIndex()) {
            if (cancelLoadRequested) {
                cancelled = true
                stopIndex = index
                break
            }
            // 进度（协程内部直接写 StateFlow，主线程即时可见）
            // v1.10.0：label 仅内部区分用途，用户侧文案不再出现「批次」字样
            val done = doneBefore + index + 1
            _loadState.value = LoadState.Loading(
                "正在加载${if (label.isNotEmpty()) "（$label）" else ""} $done/$grandTotal：${file.name}",
                progress = done.toFloat() / grandTotal,
                current = done, total = grandTotal, cancellable = true
            )
            val size = file.length()
            if (size > MAX_INPUT_BYTES) {
                tooBig.add(file.name)
                continue
            }
            // 真实堆检查（每 8 个文件一次；GC 复测防误判）：
            // 加载阶段必须给 readAssets 的对象构造留足膨胀空间
            if (index and 7 == 7 && heapUsedRatioAfterGc() > HEAP_STOP_LOADING) {
                heapStopped = true
                stopIndex = index
                break
            }
            // 内存预算预检：
            // - 单文件超过整批预算（堆的 55%）→ 任何批次都装不下，永久跳过；
            // - 本批已满装不下 → 转入下一批次（新批次全量堆，可正常装入），
            //   不能永久丢弃——否则较大的合法文件会在首批装满时被误丢。
            val retainedNow = m.retainedBytes()
            if (size > memCap) {
                overBudget.add(file.name)
                continue
            }
            if (retainedNow > 0 && retainedNow + size > memCap) {
                deferred.add(file)
                continue
            }
            try {
                val bytes = file.readBytes()
                // 读盘后再校验一次（扫描与加载之间文件可能被替换）
                if (bytes.size > MAX_INPUT_BYTES) {
                    tooBig.add(file.name)
                    continue
                }
                m.loadFileDeferred(file.name, bytes)
                loaded.add(file)
            } catch (e: Throwable) {
                // 单文件失败（含 OutOfMemoryError）不中断批量流程
                failures.add("${file.name}: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        // 无论是否中途停止：成功读入的文件全部兑现成可浏览资产（构造 + 剥离类型树）
        try {
            unconstructed = m.finishLoading { heapUsedRatioAfterGc() > HEAP_STOP_PARSING }
            strippedNodes = m.stripTypeTrees()
        } catch (e: Throwable) {
            m.errors.add("批次收尾失败：${e.message ?: e.toString()}")
        }
        System.gc()
        // 未装下的文件（本批装不下的在前、停止位置起的在后）：供下一批次继续
        val remaining = deferred + (
            if (stopIndex >= 0) files.subList(stopIndex, files.size).toList() else emptyList()
            )
        return BatchOutcome(
            loaded, failures, tooBig, overBudget, cancelled, heapStopped,
            unconstructed, strippedNodes, remaining
        )
    }

    /**
     * 链式分批加载（v1.8.0 核心）：
     *
     * ```
     * 批次 1（独立解析器）→ 读满内存安全线 → 登记为可查看批次
     *   → 驱逐旧批次腾出堆 → 批次 2（全新解析器、全量堆可用）→ …
     *   → 直到全部文件分配完毕 / 取消 / 无法推进
     * ```
     *
     * 每个批次登记后即出现在主页批次卡片中，随时可点「查看资产列表」；
     * 被驱逐的批次保留文件清单与统计，重新查看时从磁盘重解析（[openSession]）。
     */
    private suspend fun chainLoadBatches(
        files: List<java.io.File>,
        folderName: String,
        startBatchNo: Int,
        resumed: Boolean,
        truncatedCount: Int = 0
    ) {
        val grandTotal = files.size
        val memCap = folderMemCapBytes()
        var remaining = files
        var batchNo = startBatchNo - 1
        var doneBefore = 0
        var totalOk = 0
        var totalAssets = 0
        var cancelled = false
        val allFailures = ArrayList<String>()
        val allTooBig = ArrayList<String>()
        val allOverBudget = ArrayList<String>()
        val decryptNotes = LinkedHashSet<String>()

        while (remaining.isNotEmpty()) {
            batchNo++
            // 新批次需要尽量空的堆：驱逐其他可重载批次（等待 GC 真正生效再复测）
            // v1.9.0：驱逐只把旧批次对象变懒（索引保留），统一列表不丢条目
            withContext(Dispatchers.IO) { evictSessionsForNewBatch() }
            // v1.10.0：对用户只讲「第几个文件」——内部换批（腾内存）完全隐形
            _loadState.value = LoadState.Loading(
                "正在加载 $doneBefore/$grandTotal …",
                progress = doneBefore.toFloat() / grandTotal,
                current = doneBefore, total = grandTotal, cancellable = true
            )
            val m = newManager()
            val outcome = withContext(Dispatchers.IO) {
                loadBatchInternal(m, remaining, "", doneBefore, grandTotal, memCap)
            }
            cancelled = cancelled || outcome.cancelled
            totalOk += outcome.loadedFiles.size
            allFailures += outcome.failures
            allTooBig += outcome.tooBig
            allOverBudget += outcome.overBudget
            m.lastUnwrapNote?.let { decryptNotes.add(it) }

            // 登记批次（成功读入 ≥1 个文件才成批）
            if (outcome.loadedFiles.isNotEmpty()) {
                val id = nextSessionId++
                val detail = buildString {
                    if (outcome.unconstructed > 0) {
                        append("⚠ ${outcome.unconstructed} 个文件因内存不足未解析对象")
                    }
                    if (outcome.overBudget.isNotEmpty()) {
                        if (isNotEmpty()) append("；")
                        append("跳过 ${outcome.overBudget.size} 个大文件")
                    }
                    if (outcome.failures.isNotEmpty()) {
                        if (isNotEmpty()) append("；")
                        append("解析失败 ${outcome.failures.size} 个")
                    }
                }
                val session = buildSession(
                    id, "「$folderName」批次 $batchNo", outcome.loadedFiles, m, detail,
                    originKey = "folder:$folderName:$batchNo"
                )
                totalAssets += session.assetCount
                registerSession(session, m, activate = false)
            }
            doneBefore += remaining.size - outcome.remainingFiles.size
            remaining = outcome.remainingFiles

            // 终止：全部装完 / 取消 / 本批零进展（防死循环）
            if (remaining.isEmpty() || cancelled || outcome.loadedFiles.isEmpty()) break
        }

        // 收尾（v1.9.0）：加载完成 → **统一列表**——全部文件的全部资产一次列出，
        // 重对象按需装载；不再有勾选合并 / 装回 / 「装不下未包含」的概念
        _activeSessionId.value = null
        rebuildUnifiedAssets()
        if (totalOk > 0 && _sessions.value.isNotEmpty()) {
            val lazyCount = _assets.value.count { it.isLazy }
            _toast.value = "加载完成：${_assets.value.size} 个资产已列出" +
                if (lazyCount > 0) "（$lazyCount 项点开时自动从磁盘载入）" else ""
        }
        _decryptNote.value = decryptNotes.joinToString("\n").ifEmpty { null }

        val anyLoaded = totalOk > 0
        _loadState.value = when {
            !anyLoaded -> LoadState.Failed(
                buildString {
                    append("没有文件能成功加载")
                    if (allFailures.isNotEmpty()) append("：${allFailures.first()}")
                    if (allTooBig.isNotEmpty()) append("；${allTooBig.size} 个超过单文件上限")
                    if (cancelled) append("（已取消）")
                }
            )
            // v1.10.1：rebuildUnifiedAssets 在加载中不再覆盖 Loading（防汇总卡闪现），
            // 最终的 Loaded 在这里统一显式写入（文件数 = 全部批次合计）
            _sessions.value.isNotEmpty() ->
                LoadState.Loaded(_sessions.value.sumOf { it.fileCount })
            else -> LoadState.Loaded(manager.assetsFileList.size)
        }

        // v1.10.0：汇总文案只讲「文件 / 资产 / 磁盘暂存」——批次是内部实现，不再暴露
        val lazyTotal = _assets.value.count { it.isLazy }
        _folderSummary.value = buildString {
            append(if (resumed) "继续加载" else "文件夹加载")
            append("完成：成功 $totalOk 个文件、${_assets.value.size} 个资产")
            if (cancelled) append("（已取消，可继续装载剩余文件）")
            if (lazyTotal > 0) {
                append("\n其中 $lazyTotal 个资产暂存磁盘（手机内存一次装不下这么多），" +
                    "列表照常完整，点开某个资产时会自动从磁盘载入它所在的那一个文件。")
            }
            if (remaining.isNotEmpty()) {
                append("\n⚠ 还有 ${remaining.size} 个文件过大，一次装不下——点击下方按钮自动继续装载。")
            }
            if (allOverBudget.isNotEmpty()) {
                append("\n因内存预算跳过 ${allOverBudget.size} 个大文件：")
                append(allOverBudget.take(3).joinToString("、"))
                if (allOverBudget.size > 3) append(" 等")
            }
            if (allTooBig.isNotEmpty()) append("\n超过单文件上限跳过 ${allTooBig.size} 个")
            if (allFailures.isNotEmpty()) {
                append("\n解析失败 ${allFailures.size} 个：")
                append(allFailures.take(3).joinToString("；"))
                if (allFailures.size > 3) append(" 等")
            }
            if (truncatedCount > 0) {
                append("\n文件数超过 $MAX_FOLDER_FILES，已截断 $truncatedCount 个")
            }
        }
        _pendingRemainingFiles.value = remaining.takeIf { it.isNotEmpty() }
            ?.let { PendingRemainingFiles(it, folderName, batchNo + 1) }
    }

    fun consumeFolderSummary() {
        _folderSummary.value = null
    }

    // ============================ 批次查看与切换（v1.8.0） ============================

    /** 新建批次解析器（继承用户填写的 Unity 版本） */
    private fun newManager(): AssetsManager = AssetsManager().also {
        it.specifyUnityVersion = specifyUnityVersion.ifBlank { null }
    }

    /** 从解析器构建批次记录（统计资产类型分布；v1.9.0 同时生成轻量索引） */
    private fun buildSession(
        id: Long,
        title: String,
        files: List<java.io.File>,
        m: AssetsManager,
        summary: String = "",
        originKey: String = ""
    ): BatchSession {
        val items = collectAssets(m, sessionId = id)
        return BatchSession(
            id = id,
            title = title,
            files = files,
            fileCount = m.assetsFileList.size,
            assetCount = items.size,
            textureCount = items.count { it.type.value == 28 },
            spriteCount = items.count { it.type.value == 213 || it.type.value == 68 },
            audioCount = items.count { it.type.value == 83 },
            textCount = items.count { it.type.value == 49 },
            errorCount = m.errors.size,
            retainedBytes = m.retainedBytes(),
            summary = summary,
            live = true,
            canReload = files.isNotEmpty(),
            originKey = originKey,
            index = items
        )
    }

    /** 登记批次（同源重复加载自动替换旧记录，如手动解密重试） */
    private fun registerSession(s: BatchSession, m: AssetsManager, activate: Boolean) {
        sessionManagers[s.id] = m
        val kept = if (s.originKey.isNotEmpty()) {
            _sessions.value.filterNot { it.originKey == s.originKey }
        } else _sessions.value
        _sessions.value = kept + s
        // v1.9.0：新批次并入统一列表（列表 = 全部批次索引的并集，永不因内存丢条目）
        rebuildUnifiedAssets()
        if (activate) {
            _activeSessionId.value = s.id
            manager = m
        }
    }

    /** 激活批次：v1.9.0 已无单批次视图——仅保留指针语义给旧调用方 */
    private fun activateSession(id: Long, m: AssetsManager) {
        manager = m
        _activeSessionId.value = id
        rebuildUnifiedAssets()
    }

    /**
     * 驱逐一个批次（释放对象内存；IO 线程调用）。
     * v1.9.0：**索引保留**——批次条目只清空对象引用（copy(obj=null)），
     * 统一列表完整保留该批次的全部资产（懒条目，点开时按需装载）。
     */
    private fun evictSession(id: Long) {
        sessionManagers.remove(id)
        _sessions.value = _sessions.value.map {
            if (it.id == id) it.copy(
                live = false,
                index = it.index.map { entry -> entry.copy(obj = null) }
            ) else it
        }
        if (_activeSessionId.value == id) {
            _activeSessionId.value = null
            manager = sessionManagers.values.lastOrNull() ?: newManager()
        }
        rebuildUnifiedAssets()
    }

    /**
     * 为新批次腾内存：驱逐可重载批次直到堆回落到 [HEAP_EVICT_LINE] 以下
     * （IO 线程调用；[heapUsedRatioSettled] 等待 GC 真正生效后复测）。
     *
     * 这保证新批次有接近全量的堆可用——每批装入量与前一批相当，
     * 链式推进确定可终止。SAF Uri 批次（canReload=false）不驱逐。
     * v1.9.0：驱逐不再丢失列表条目（索引保留），只是对象变懒。
     */
    private fun evictSessionsForNewBatch() {
        var ratio = heapUsedRatioSettled()
        while (ratio > HEAP_EVICT_LINE) {
            val victim = _sessions.value.firstOrNull { it.live && it.canReload } ?: break
            evictSession(victim.id)
            ratio = heapUsedRatioSettled()
        }
    }

    /**
     * 重建统一资产列表（v1.9.0）：**全部批次**的并集——
     * 驻留批次直接取解析器对象（obj 已附）；已驱逐批次取常驻轻量索引
     * （obj=null 懒条目，按需装载映射 [demandAttach] 里已装回的回填对象）。
     *
     * 这是 v1.9.0 的核心：看列表与内存彻底解耦，不再有
     * 「勾选组合 / 装不下未包含 / 换批查看」的概念——列表永远完整。
     */
    private fun rebuildUnifiedAssets() {
        val sessions = _sessions.value
        if (sessions.isEmpty()) {
            _assets.value = emptyList()
            _loadedFileName.value = ""
            _loadState.value = LoadState.Idle
            return
        }
        val items = ArrayList<AssetItem>(sessions.sumOf { it.index.size.coerceAtLeast(it.assetCount) })
        for (s in sessions) {
            val m = sessionManagers[s.id]
            if (s.live && m != null) {
                items += collectAssets(m, sessionId = s.id)
            } else {
                items += s.index.map { it.copy(obj = demandAttach[it.id]) }
            }
        }
        _assets.value = items.sortedWith(
            compareByDescending<AssetItem> { it.type.value }.thenBy { it.name }
        )
        _loadErrors.value = sessions.mapNotNull { sessionManagers[it.id]?.errors }
            .flatten().distinct()
        manager = sessionManagers.values.lastOrNull() ?: manager
        val totalAssets = _assets.value.size
        // v1.10.0：标题只讲文件与资产数——「批次」概念对用户彻底隐形
        _loadedFileName.value = "${sessions.sumOf { it.fileCount }} 个文件 · $totalAssets 项"
        // v1.10.1（图一修复）：链式加载中每个批次登记都会走这里——若无条件写 Loaded，
        // 下一批的进度更新又写回 Loading，主页就会在「进度卡 ⇄ 查看资产汇总卡」间
        // 时不时闪现。加载进行中保持 Loading，最终 Loaded 由 chainLoadBatches 收尾统一写
        if (_loadState.value !is LoadState.Loading) {
            _loadState.value = LoadState.Loaded(sessions.sumOf { it.fileCount })
        }
    }

    /**
     * 打开统一资产列表（v1.9.0）：不再有批次勾选 / 合并装回——
     * 列表永远是全部批次的全部资产；重对象在点开预览/导出时按需装载。
     */
    fun openUnifiedList(onReady: () -> Unit) {
        if (_sessions.value.isEmpty()) {
            _toast.value = "还没有加载任何文件"
            return
        }
        rebuildUnifiedAssets()
        onReady()
    }

    /**
     * 详情页触发按需装载（v1.9.0）：懒条目点开预览时自动调用——
     * 只从磁盘装回该资产所在的**一个文件**（秒级），补上对象后列表自动刷新。
     *
     * v1.10.1：列表点击与详情页 LaunchedEffect 都会调用（提前装载优化），
     * 用 [demandInFlight] 防重入——同一条目并发装两次是纯浪费（重复读盘+解析）。
     */
    private val demandInFlight =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    fun requestAssetLive(id: String) {
        val item = _assets.value.find { it.id == id } ?: return
        if (item.obj != null) return
        if (!demandInFlight.add(id)) return   // 该条目的装载已在进行中
        _demandState.value = DemandState.Loading(item.name)
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { ensureItemLive(item) }
                _demandState.value = when {
                    loaded?.obj != null -> DemandState.Ready
                    else -> DemandState.Failed(item.name, "该文件无法从磁盘装回")
                }
            } finally {
                demandInFlight.remove(id)
            }
        }
    }

    /**
     * 确保一个条目的对象在内存（IO 线程调用；批量导出 / 替换逐条复用）。
     *
     * - 已驻留批次的条目直接返回；
     * - 懒条目定位其来源文件（containerPath 文件名匹配批次文件清单），
     *   用容量 1 的按需解析器只装这一个文件（走 60%/78% 内存防线），
     *   再按 fileName+pathID 精确找回对象并回填列表。
     * 返回带对象的条目；失败返回 null（调用方跳过并记录）。
     */
    private fun ensureItemLive(item: AssetItem): AssetItem? {
        item.obj?.let { return item }
        val s = _sessions.value.find { it.id == item.sessionId } ?: return null
        if (!s.canReload || s.files.isEmpty()) return null
        // 定位来源文件：优先 containerPath 文件名精确匹配，退回批次首个文件
        val wanted = java.io.File(item.containerPath).name
        val src = s.files.firstOrNull { it.name == wanted } ?: s.files.first()
        if (demandManagerPath != src.absolutePath) {
            dropDemandManager()
            // v1.10.1（图二卡顿修复）：旧实现这里无条件 System.gc()——阻塞式全量
            // 回收 + 全局 STW 暂停，用户正盯着详情页等预览时表现成「卡一下」。
            // 改为仅在堆快速读数偏高时才回收（低压力路径零强制 GC）
            if (heapUsedRatioFast() > 0.5f) System.gc()
            val m = newManager()
            try {
                val bytes = src.readBytes()
                m.loadFileDeferred(src.name, bytes)
                // v1.10.1：按需路径的堆防线换成 demandHeapGuard——
                // 未超线不做任何显式 GC（旧 heapUsedRatioAfterGc 用量过半就 GC）
                m.finishLoading(::demandHeapGuard)
                m.stripTypeTrees()
            } catch (e: Throwable) {
                return null
            }
            if (m.assetsFileList.isEmpty()) return null
            demandManager = m
            demandManagerPath = src.absolutePath
        }
        val m = demandManager ?: return null
        val found = m.assetsFileList
            .firstOrNull { it.fileName == item.fileName }?.objects
            ?.firstOrNull { it.m_PathID == item.pathID }
            // 单文件定位失败时兜底：跨内嵌文件按 pathID 再找一次
            ?: m.assetsFileList.flatMap { f -> f.objects }.firstOrNull { it.m_PathID == item.pathID }
            ?: return null
        demandAttach[item.id] = found
        // v1.10.0：按需缓存上限（防慢泄漏）——浏览/导出大量懒资产时，回填对象若
        // 无限累积会把已释放批次的内存重新钉死在堆上；超限即淘汰最旧一半并
        // 重建列表（被淘汰条目自动降回懒状态，再次点开时重新装载，行为无损）
        if (demandAttach.size > ON_DEMAND_CACHE_MAX) {
            val dropCount = demandAttach.size / 2
            val iter = demandAttach.entries.iterator()
            var dropped = 0
            while (dropped < dropCount && iter.hasNext()) {
                iter.next(); iter.remove(); dropped++
            }
            rebuildUnifiedAssets()
        }
        val updated = item.copy(obj = found)
        // 回填统一列表（StateFlow 写入线程安全；UI 自动重组显示预览）
        _assets.value = _assets.value.map { if (it.id == item.id) updated else it }
        return updated
    }

    /*
     * v1.9.0 起「勾选合并 / 换批查看 / 合并装回」整套流程已删除：
     * 统一列表（[rebuildUnifiedAssets]）永远包含全部批次的全部资产，
     * 重对象按需装载（[ensureItemLive]），查看不再受内存限制。
     */

    /**
     * 移除单个批次（释放其内存与索引，卡片从主页消失）。
     * 统一列表随重建剔除该批次的全部条目。
     */
    fun removeSession(id: Long) {
        sessionManagers.remove(id)
        _sessions.value = _sessions.value.filterNot { it.id == id }
        if (_activeSessionId.value == id) {
            _activeSessionId.value = null
            manager = sessionManagers.values.lastOrNull() ?: newManager()
        }
        rebuildUnifiedAssets()
        if (_sessions.value.isEmpty()) {
            _folderSummary.value = null
            _pendingRemainingFiles.value = null
        }
        System.gc()
    }

    /** 清空全部批次（释放所有内存，回到初始状态） */
    fun clearAllSessions() {
        sessionManagers.clear()
        dropDemandManager()
        manager = newManager()
        _sessions.value = emptyList()
        _activeSessionId.value = null
        _mergedView.value = false
        _demandState.value = DemandState.Idle
        _assets.value = emptyList()
        _loadErrors.value = emptyList()
        _loadedFileName.value = ""
        _folderSummary.value = null
        _pendingRemainingFiles.value = null
        _loadState.value = LoadState.Idle
        System.gc()
    }

    /** 单次文件加载（loadUris / loadFiles）收尾：登记为一个批次并激活 */
    private fun applySingleLoad(
        name: String?,
        m: AssetsManager,
        errors: List<String>,
        canReload: Boolean,
        originKey: String
    ) {
        if (name == null) {
            _loadState.value = LoadState.Failed(errors.firstOrNull() ?: "未知错误")
            return
        }
        val items = collectAssets(m)
        val id = nextSessionId++
        val session = buildSession(
            id, name, if (canReload) lastFiles else emptyList(), m, originKey = originKey
        ).copy(canReload = canReload)
        registerSession(session, m, activate = true)
        _decryptNote.value = m.lastUnwrapNote
        _loadState.value = if (items.isEmpty() && m.errors.isNotEmpty()) {
            LoadState.Failed(m.errors.joinToString("\n"))
        } else {
            // v1.10.2：文件数 = 全部批次合计（旧实现只报本次新文件数，
            // 已加载过内容时与标题「N 个文件 · M 项」对不上）
            LoadState.Loaded(_sessions.value.sumOf { it.fileCount })
        }
    }

    // ============================ 批量导出 ============================

    /** 批量导出进度（AssetListScreen 观察；null/Idle = 未在导出） */
    sealed interface ExportState {
        data object Idle : ExportState
        data class Exporting(
            val current: Int,
            val total: Int,
            val name: String,
            /** 0..1 */
            val progress: Float
        ) : ExportState
        data class Done(
            val okCount: Int,
            val failures: List<String>,   // "资产名: 原因"
            val dirPath: String,
            val cancelled: Boolean
        ) : ExportState
    }

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    @Volatile private var cancelExportRequested = false

    fun requestCancelExport() {
        cancelExportRequested = true
    }

    fun consumeExportState() {
        _exportState.value = ExportState.Idle
    }

    /**
     * 批量导出资产到文件夹（复用单资产导出器，按类型自动选格式：
     * 贴图→PNG、模型→OBJ、音频→WAV、文本/转储→TXT 等）。
     *
     * 防崩溃：
     * - 逐个导出，单个失败（含 OOM）只记录不中断；
     * - 每 8 个资产主动 GC 一次——贴图解码会产生大 Bitmap，及时回收
     *   避免峰值叠加；
     * - 文件名去非法字符 + 重名自动加序号；
     * - 全程可取消。
     */
    fun batchExport(dir: java.io.File, items: List<AssetItem>) {
        if (items.isEmpty()) {
            _exportState.value = ExportState.Done(0, emptyList(), dir.absolutePath, false)
            return
        }
        cancelExportRequested = false
        viewModelScope.launch {
            val total = items.size
            _exportState.value = ExportState.Exporting(0, total, "", 0f)
            val done = withContext(Dispatchers.IO) {
                batchExportInternal(dir, items)
            }
            _exportState.value = done
        }
    }

    private fun batchExportInternal(
        dir: java.io.File,
        items: List<AssetItem>
    ): ExportState.Done {
        val failures = ArrayList<String>()
        var ok = 0
        var cancelled = false
        val usedNames = HashSet<String>()

        // v1.10.0：导出顺序优化——已驻留的先导（零读盘），懒条目按「来源文件」聚簇。
        // 懒装载按文件缓存解析器（一次读盘装整个文件），若按类型/名称散排，
        // 同一文件的 N 个资产会触发 N 次完整读盘；聚簇后每文件只读一次。
        val ordered = items.sortedWith(
            compareBy<AssetItem> { it.isLazy }
                .thenBy { it.sessionId }
                .thenBy { java.io.File(it.containerPath).name }
        )
        val total = ordered.size

        fun uniqueName(base: String, ext: String): String {
            var candidate = "$base.$ext"
            var seq = 2
            while (!usedNames.add(candidate.lowercase())) {
                candidate = "${base}_$seq.$ext"
                seq++
            }
            return candidate
        }

        for ((index, item) in ordered.withIndex()) {
            if (cancelExportRequested) {
                cancelled = true
                break
            }
            _exportState.value = ExportState.Exporting(
                current = index + 1,
                total = total,
                name = item.name,
                progress = (index + 1).toFloat() / total
            )
            try {
                // v1.9.0：懒条目先按需装载（只装该资产所在的一个文件；同文件相邻导出复用）
                val live = if (item.isLazy) ensureItemLive(item) else item
                val obj = live?.obj
                if (obj == null) {
                    failures.add("${item.name}: 从磁盘按需装载失败，已跳过")
                    continue
                }
                // 文件名：资产名去非法字符；空名回退 pathID
                val rawName = item.name.ifBlank { "asset_${item.pathID}" }
                val safeBase = rawName.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
                    .trim().take(120).ifBlank { "asset_${item.pathID}" }
                val ext = AssetExporter.suggestedExtension(obj)
                val file = java.io.File(dir, uniqueName(safeBase, ext))
                val result = AssetExporter.exportToFile(obj, file)
                if (result.success) ok++ else failures.add("${item.name}: ${result.message}")
            } catch (e: Throwable) {
                // 单资产失败（含 OOM）不中断批量导出
                failures.add("${item.name}: ${e.message ?: e.javaClass.simpleName}")
            }
            // 贴图解码的 Bitmap 等临时对象及时回收，防峰值叠加
            if (index and 7 == 7) System.gc()
        }
        // 批量导出结束：按需解析器用完即弃（单文件级内存占用不常驻）
        dropDemandManager()
        return ExportState.Done(ok, failures, dir.absolutePath, cancelled)
    }

    /** 用手动解密参数重新加载最近一次选择的文件（File 路径优先，其次 SAF Uri） */
    fun reloadWithDecrypt(context: Context, spec: ManualDecryptSpec) {
        manualDecryptSpec = spec
        when {
            lastFiles.isNotEmpty() -> loadFiles(lastFiles)
            lastUris.isNotEmpty() -> loadUris(context, lastUris)
        }
    }

    /** 收集指定解析器内的全部资产（默认当前活跃批次的解析器；sessionId 写入条目供合并视图区分批次） */
    private fun collectAssets(
        m: AssetsManager = manager,
        sessionId: Long = _activeSessionId.value ?: 0L
    ): List<AssetItem> {
        val items = ArrayList<AssetItem>()
        for (file in m.assetsFileList) {
            for (obj in file.objects) {
                items.add(
                    AssetItem(
                        obj = obj,
                        pathID = obj.m_PathID,
                        name = obj.displayName,
                        type = obj.reader.type,
                        byteSize = obj.reader.byteSize,
                        fileName = file.fileName,
                        containerPath = file.originalPath,
                        sessionId = sessionId
                    )
                )
            }
        }
        return items.sortedWith(compareByDescending<AssetItem> { it.type.value }.thenBy { it.name })
    }

    private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? {
        return try {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            } ?: uri.lastPathSegment
        } catch (e: Exception) {
            uri.lastPathSegment
        }
    }

    /** 查询文件大小（字节）；查询失败返回 -1 表示未知（放行，由后续读取兜底） */
    private fun android.content.ContentResolver.queryFileSize(uri: Uri): Long {
        return try {
            query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getLong(idx) else -1L
            } ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }

    // ============================ 贴图解码预览 ============================

    suspend fun decodeTexture(texture: Texture2D): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val converter = TextureConverter(texture)
            if (texture.m_Width * texture.m_Height > 33_554_432) { // 16M 像素上限
                null
            } else {
                val pixels = converter.decodeToPixels() ?: return@withContext null
                // Unity 纹理 row 0 = 底部（OpenGL 习惯），Bitmap row 0 = 顶部
                // 对应 C# RotateFlip(RotateNoneFlipY)；漏掉此步贴图上下颠倒（v1.7.7 修复）
                PixelFlip.flipVerticalInPlace(pixels, texture.m_Width, texture.m_Height)
                Bitmap.createBitmap(pixels, texture.m_Width, texture.m_Height, Bitmap.Config.ARGB_8888)
            }
        } catch (e: Throwable) {
            null
        }
    }

    /** Sprite 预览：从图集纹理中裁剪 textureRect 区域 */
    suspend fun decodeSprite(sprite: Sprite): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val info = sprite.getTextureInfo() ?: return@withContext null
            val full = decodeTexture(info.texture) ?: return@withContext null
            val rect = info.textureRect
            val x = rect.x.toInt().coerceIn(0, full.width - 1)
            val y = rect.y.toInt().coerceIn(0, full.height - 1)
            val w = rect.width.toInt().coerceAtMost(full.width - x).coerceAtLeast(1)
            val h = rect.height.toInt().coerceAtMost(full.height - y).coerceAtLeast(1)
            // Unity 纹理原点在左下，Bitmap 原点在左上
            val top = (full.height - y - h).coerceIn(0, full.height - 1)
            Bitmap.createBitmap(full, x, top, w, h)
        } catch (e: Throwable) {
            null
        }
    }

    // ============================ 资产替换（贴图 / 模型） ============================

    fun replaceTexture(texture: Texture2D, bitmap: Bitmap, keepFormat: Boolean, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    AssetReplacer.replaceTexture(texture, bitmap, keepFormat)
                } catch (e: Exception) {
                    null
                }
            }
            if (result == null) {
                onDone(false, "替换失败，请重试或使用其他图片")
            } else {
                _pendingSave.value = PendingSave(result.bytes, result.suggestedName)
                val fmtNote = if (result.keptOriginalFormat) "原格式" else "RGBA32"
                onDone(
                    true,
                    "替换成功（${result.usedFormat} / ${result.mipCount} 级 mip / 格式策略: $fmtNote），请另存文件"
                )
            }
        }
    }

    /**
     * 批量替换贴图（游戏美化包）：
     * 文件夹内图片按文件名自动匹配已选贴图 → 逐张替换 → 按容器重打包。
     * 单容器输出原文件，多容器自动打包 ZIP；结果暂存 pendingSave 等待另存。
     */
    fun replaceTexturesBatch(
        entries: List<AssetReplacer.BatchEntry>,
        onDone: (ok: Boolean, message: String) -> Unit
    ) {
        viewModelScope.launch {
            val (batch, err) = withContext(Dispatchers.IO) {
                try {
                    AssetReplacer.replaceTexturesBatch(entries) to null
                } catch (e: OutOfMemoryError) {
                    null to "内存不足：贴图或原文件过大，建议减少一次替换的数量"
                } catch (e: Exception) {
                    null to (e.message ?: "批量替换失败")
                }
            }
            if (batch == null) {
                onDone(false, "批量替换失败：$err")
            } else {
                _pendingSave.value = PendingSave(batch.bytes, batch.suggestedName)
                val msg = buildString {
                    append("替换完成：成功 ${batch.succeeded.size} 张")
                    if (batch.failed.isNotEmpty()) append("，失败 ${batch.failed.size} 张")
                    append("（原格式 ${batch.keptOriginalCount}，回退 RGBA32 ${batch.fallbackCount}）")
                    if (batch.outputs.size == 1) {
                        append("\n输出 1 个文件，请另存后替换游戏内原文件")
                    } else {
                        append("\n输出 ${batch.outputs.size} 个文件，已打包为 ZIP，解压后按原文件名逐个替换游戏文件")
                    }
                    if (batch.failed.isNotEmpty()) {
                        append("\n失败原因：")
                        append(batch.failed.take(3).joinToString("；") { "${it.textureName}: ${it.reason}" })
                        if (batch.failed.size > 3) append(" 等 ${batch.failed.size} 项")
                    }
                }
                onDone(true, msg)
            }
        }
    }

    /** 用 OBJ 文本替换 Mesh（子网格/顶点/索引/包围盒全部重写，名称保留） */
    fun replaceMesh(mesh: Mesh, objText: String, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val (repack, err) = withContext(Dispatchers.IO) {
                try {
                    AssetReplacer.replaceMesh(mesh, objText) to null
                } catch (e: OutOfMemoryError) {
                    // 大模型 + 大 bundle 重打包可能触发，给用户明确提示而非崩溃
                    null to "内存不足：模型或原文件过大，建议减少顶点数后重试"
                } catch (e: Exception) {
                    null to (e.message ?: "替换失败")
                }
            }
            if (repack == null) {
                onDone(false, "模型替换失败：$err")
            } else {
                _pendingSave.value = PendingSave(repack.bytes, repack.suggestedName)
                onDone(true, "替换成功，请另存文件")
            }
        }
    }

    fun savePendingResult(context: Context, uri: Uri, onDone: (Boolean, String) -> Unit) {
        val result = _pendingSave.value ?: run {
            onDone(false, "没有待保存的替换结果")
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        out.write(result.bytes)
                        out.flush()
                    } != null
                } catch (e: Exception) {
                    false
                }
            }
            if (ok) {
                _pendingSave.value = null
                onDone(true, "已保存（${formatBytes(result.bytes.size.toLong())}）")
            } else {
                onDone(false, "保存失败，请检查存储权限")
            }
        }
    }

    // ============================ MCP 服务器 ============================

    /**
     * 启动 MCP 服务器（AI 助手可通过 HTTP 工具查询/导出当前已加载的资产）。
     *
     * 修复要点：
     * 1. ServerSocket.bind 是网络操作，主线程调用会被 Android 拦截抛
     *    NetworkOnMainThreadException → 必须放到 Dispatchers.IO 后台执行；
     * 2. 检查 startServer() 的返回值：失败时不再误报"已启动"，
     *    也不把"未运行"状态串写进 mcpStatus（否则设置页会错乱地显示"停止服务器"按钮）。
     */
    fun startMcp(context: Context) {
        if (_mcpStarting.value) return // 启动中，忽略重复点击
        _mcpStarting.value = true
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val exportDir = java.io.File(context.filesDir, "mcp_export").apply { mkdirs() }
                    McpManager.sourceProvider = { AppMcpSource(manager, exportDir) }
                    McpManager.startServer()
                } catch (e: Exception) {
                    android.util.Log.w("MainViewModel", "MCP start failed", e)
                    false
                }
            }
            val port = McpManager.DEFAULT_PORT
            if (ok) {
                // 获取局域网 IP 也在 IO 线程做
                val ip = withContext(Dispatchers.IO) { detectLanIp() }
                _mcpStatus.value = buildString {
                    append("MCP 服务器运行中（端口 $port）")
                    if (ip != null) append("\n连接地址：http://$ip:$port")
                    append("\nAI 客户端需与手机连接同一 Wi-Fi")
                }
                _toast.value = "MCP 服务器已启动（端口 $port）"
            } else {
                _mcpStatus.value = null
                _toast.value = "MCP 启动失败：${McpManager.lastStartError ?: "未知原因"}"
            }
            _mcpStarting.value = false
        }
    }

    fun stopMcp() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    McpManager.stopServer()
                } catch (_: Exception) {
                }
            }
            _mcpStatus.value = null
            _toast.value = "MCP 服务器已停止"
        }
    }

    /** 局域网 IPv4（优先 wlan/eth 接口），供 AI 客户端连接用；取不到返回 null */
    private fun detectLanIp(): String? {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return null
            var fallback: String? = null
            for (nic in interfaces) {
                if (!nic.isUp || nic.isLoopback) continue
                for (addr in nic.inetAddresses) {
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        if (nic.name.startsWith("wlan") || nic.name.startsWith("eth")) {
                            return addr.hostAddress
                        }
                        if (fallback == null) fallback = addr.hostAddress
                    }
                }
            }
            fallback
        } catch (e: Exception) {
            null
        }
    }

    // ============================ 导出 ============================

    fun exportAsset(context: Context, item: AssetItem, uri: Uri, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            // v1.10.0：懒条目先按需装载（只读它所在的一个文件），再导出
            val live = withContext(Dispatchers.IO) { if (item.isLazy) ensureItemLive(item) else item }
            val obj = live?.obj
            if (obj == null) {
                onDone(false, "该资产无法从磁盘装回（来源文件可能已变化）")
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                try {
                    AssetExporter.export(obj, context.contentResolver, uri)
                } catch (e: Exception) {
                    AssetExporter.ExportResult(false, e.message ?: "导出失败")
                }
            }
            onDone(result.success, result.message)
        }
    }

    /** 导出到应用内文件管理器选定的 File（不经 SAF，后缀完全自控） */
    fun exportAssetToFile(item: AssetItem, file: java.io.File, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            // v1.10.0：懒条目先按需装载（只读它所在的一个文件），再导出
            val live = withContext(Dispatchers.IO) { if (item.isLazy) ensureItemLive(item) else item }
            val obj = live?.obj
            if (obj == null) {
                onDone(false, "该资产无法从磁盘装回（来源文件可能已变化）")
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                try {
                    AssetExporter.exportToFile(obj, file)
                } catch (e: Exception) {
                    AssetExporter.ExportResult(false, e.message ?: "导出失败")
                }
            }
            onDone(result.success, result.message)
        }
    }

    /** 将替换重打包结果直写到 File */
    fun savePendingToFile(file: java.io.File, onDone: (Boolean, String) -> Unit) {
        val result = _pendingSave.value ?: run {
            onDone(false, "没有待保存的替换结果")
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    file.parentFile?.mkdirs()
                    java.io.FileOutputStream(file).use { out ->
                        out.write(result.bytes)
                        out.flush()
                    }
                    true
                } catch (e: Exception) {
                    false
                }
            }
            if (ok) {
                _pendingSave.value = null
                onDone(true, "已保存到 ${file.absolutePath}（${formatBytes(result.bytes.size.toLong())}）")
            } else {
                onDone(false, "保存失败，请检查存储权限")
            }
        }
    }

    // ============================ 工具 ============================

    companion object {
        /** 单文件加载上限：Android 应用堆通常仅 256~512MB，整读更大文件必然 OOM */
        const val MAX_INPUT_BYTES: Long = 768L * 1024 * 1024

        /**
         * 文件夹单次批量加载的文件数绝对上限。
         *
         * 设计原则：真正的约束是内存预算（见 [folderMemCapBytes]）而不是文件数。
         * 游戏美化包目录常见数千个小 bundle（每个十几 KB），总大小远小于内存
         * 预算，全部加载毫无压力；因此数量上限只作为病态目录（如误选存储根目录、
         * 几十万个文件）的最后保险。截断时保留较小的文件。
         */
        const val MAX_FOLDER_FILES: Int = 5000

        /**
         * 批量加载内存软上限占应用堆的比例。
         * 保留 45% 给单文件解析期的临时峰值（解压/复制）与 UI。
         * largeHeap 设备通常有 512MB 堆 → 上限约 280MB 驻留。
         * （这只是文件字节维度的辅助预算；真正的防线是
         * [HEAP_STOP_LOADING] / [HEAP_STOP_PARSING] 的真实堆检查）
         */
        const val FOLDER_MEM_RATIO: Float = 0.55f

        /**
         * 批量加载「读取阶段」的堆安全线。
         * 文件字节只是内存小头——后续对象构造会再膨胀数倍，因此读取阶段
         * 必须在堆用量较低时就停下，给 readAssets 留足膨胀空间。
         */
        const val HEAP_STOP_LOADING: Float = 0.60f

        /**
         * 「对象构造阶段」的堆安全线。对象构造是内存大头，此阶段本身
         * 需要吃内存，阈值放高；但必须给系统监控线程与 UI 留出
         * 余量（约 22%，512MB 堆即 ~110MB），杜绝厂商系统线程
         * （如 ColorOS oplus_force_gc_t）在堆满时分配失败导致的闪退。
         */
        const val HEAP_STOP_PARSING: Float = 0.78f

        /**
         * 「为新批次腾内存」的驱逐线（v1.8.0 批次架构）。
         * 链式加载下一批次前，驱逐已驻留的可重载批次直到堆回落到此线以下：
         * 低于 20% 时新批次可用的堆接近全量（读取阶段到 60% 停、
         * 构造阶段到 78% 停），保证每批装入量与前一批相当、链式推进可终止。
         */
        const val HEAP_EVICT_LINE: Float = 0.20f

        /**
         * 堆占比的「已沉降」判定线：[heapUsedRatioSettled] 等待 GC 生效时，
         * 占比高于此线才继续 GC + 等待 + 复测（低频决策点专用，
         * 不进入每 8 个文件的热路径）。
         */
        const val HEAP_CONTINUE_LOADING: Float = 0.70f

        /**
         * 按需装载回填对象的缓存上限（v1.10.0）。
         * 懒条目点开后其对象会回填进统一列表；若无上限，长时间浏览会把
         * 已释放批次的内存重新钉死。超限淘汰最旧一半（条目降回懒状态）。
         */
        const val ON_DEMAND_CACHE_MAX: Int = 48

        /** 文件夹批量加载的驻留内存软上限（字节），按设备实际堆动态计算 */
        fun folderMemCapBytes(): Long {
            val heap = Runtime.getRuntime().maxMemory()
            return (heap * FOLDER_MEM_RATIO).toLong().coerceIn(
                96L * 1024 * 1024,   // 下限 96MB（小堆设备也至少能装一批小文件）
                768L * 1024 * 1024   // 上限 768MB
            )
        }

        fun formatBytes(size: Long): String = when {
            size >= 1 shl 20 -> "%.2f MB".format(size / 1048576.0)
            size >= 1 shl 10 -> "%.2f KB".format(size / 1024.0)
            else -> "$size B"
        }
    }
}

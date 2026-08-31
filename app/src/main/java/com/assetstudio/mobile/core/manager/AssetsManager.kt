package com.assetstudio.mobile.core.manager

/*
 * 来源: AssetStudio/AssetsManager.cs
 *
 * 资源加载管理器（适配 Android 内存模型）：
 * - C# 版基于文件系统（LoadFiles/LoadFolder/外部 .resS 磁盘搜索），
 *   这里改为 loadFile(fileName, data) 直接从内存字节加载，可多次调用。
 * - 外部资源（.resS 等）不再扫描磁盘，改为 externalResourceOpener 回调
 *   由 UI 层提供；解析结果缓存进 resourceFileReaders。
 * - readAssets 阶段单个对象解析失败不中断，记录进 errors。
 */

import com.assetstudio.mobile.core.ClassIDType
import com.assetstudio.mobile.core.ObjectReader
import com.assetstudio.mobile.core.bundle.BundleFile
import com.assetstudio.mobile.core.bundle.WebFile
import com.assetstudio.mobile.core.classes.Animation
import com.assetstudio.mobile.core.classes.AnimationClip
import com.assetstudio.mobile.core.classes.Animator
import com.assetstudio.mobile.core.classes.AnimatorController
import com.assetstudio.mobile.core.classes.AnimatorOverrideController
import com.assetstudio.mobile.core.classes.AssetBundle
import com.assetstudio.mobile.core.classes.AudioClip
import com.assetstudio.mobile.core.classes.Avatar
import com.assetstudio.mobile.core.classes.Font
import com.assetstudio.mobile.core.classes.GameObject
import com.assetstudio.mobile.core.classes.Material
import com.assetstudio.mobile.core.classes.Mesh
import com.assetstudio.mobile.core.classes.MeshFilter
import com.assetstudio.mobile.core.classes.MeshRenderer
import com.assetstudio.mobile.core.classes.MonoBehaviour
import com.assetstudio.mobile.core.classes.MonoScript
import com.assetstudio.mobile.core.classes.MovieTexture
import com.assetstudio.mobile.core.classes.PlayerSettings
import com.assetstudio.mobile.core.classes.ResourceManager
import com.assetstudio.mobile.core.classes.RectTransform
import com.assetstudio.mobile.core.classes.Shader
import com.assetstudio.mobile.core.classes.SkinnedMeshRenderer
import com.assetstudio.mobile.core.classes.Sprite
import com.assetstudio.mobile.core.classes.SpriteAtlas
import com.assetstudio.mobile.core.classes.TextAsset
import com.assetstudio.mobile.core.classes.Texture2D
import com.assetstudio.mobile.core.classes.Transform
import com.assetstudio.mobile.core.classes.UnityObject
import com.assetstudio.mobile.core.classes.VideoClip
import com.assetstudio.mobile.core.crypto.EncryptedBundleDecoder
import com.assetstudio.mobile.core.crypto.KhBundleDecoder
import com.assetstudio.mobile.core.io.Brotli
import com.assetstudio.mobile.core.io.FileReader
import com.assetstudio.mobile.core.io.FileType
import com.assetstudio.mobile.core.io.FileTypeDetector
import com.assetstudio.mobile.core.io.Gzip
import com.assetstudio.mobile.core.io.getFileName
import com.assetstudio.mobile.core.serialized.SerializedFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class AssetsManager {

    /** C# SpecifyUnityVersion：用户指定的 Unity 版本（文件版本被剥离时必需） */
    var specifyUnityVersion: String? = null

    /** 已加载的序列化文件 */
    val assetsFileList: MutableList<SerializedFile> = ArrayList()

    /** 资源文件（.resS/.resource 等）内存表，键为文件名（忽略大小写查找） */
    val resourceFileReaders: LinkedHashMap<String, ByteArray> = LinkedHashMap()

    /**
     * 外部资源加载回调：直接打开 .assets 文件时，其 .resS 不在内存中，
     * 由 UI 层按文件名提供字节（如从磁盘/压缩包按需读取），结果会缓存。
     */
    var externalResourceOpener: ((String) -> ByteArray?)? = null

    /** 对象解析失败的错误记录（文件名/类型/PathID/异常信息） */
    val errors: MutableList<String> = ArrayList()

    /** 已完成对象构造的文件（避免重复 loadFile 时重复解析） */
    private val readObjectFiles: LinkedHashSet<SerializedFile> = LinkedHashSet()

    /** C# assetsFileListHash（忽略大小写去重） */
    private val assetsFileListHash: MutableSet<String> = HashSet()

    /** C# importFilesHash */
    private val importFilesHash: MutableSet<String> = HashSet()

    /**
     * 自动解包备选数据（键 = 原始文件 fullPath）：
     * XOR 加密可能只作用于头部区域，首选"全文件解密"解析失败时按序重试。
     */
    private val pendingUnwrapFallbacks: MutableMap<String, List<ByteArray>> = LinkedHashMap()

    /** 最近一次自动解包的说明（UI 展示用）：null = 未触发 */
    var lastUnwrapNote: String? = null
        private set

    // ============================ 对外入口 ============================

    /**
     * 从内存加载一个文件（可多次调用，每次完成后自动 readAssets + processAssets）。
     * [unityVersion] 对应 C# LoadAssetsFromMemory 的 unityVersion 参数
     * （来自 bundle 头的 unityRevision，用于补全低版本文件）。
     */
    fun loadFile(fileName: String, data: ByteArray, unityVersion: String? = null) {
        loadFileDeferred(fileName, data, unityVersion)
        readAssets()
        processAssets()
    }

    /**
     * 只加载不后处理：批量加载（文件夹模式）专用。
     *
     * 原因：loadFile 每次调用都会跑一遍 processAssets()，其复杂度是
     * O(全部已加载对象)，N 个文件逐个加载时总代价 O(N²)，文件一多
     * 不但卡顿还会放大内存压力。批量场景改为全部 loadFileDeferred 后，
     * 统一调用一次 [finishLoading] 收尾。
     */
    fun loadFileDeferred(fileName: String, data: ByteArray, unityVersion: String? = null) {
        val before = assetsFileList.size
        loadFile(FileReader(fileName, data), unityVersion, null)
        // 记录根文件名：本次加载新产生的所有 SerializedFile 均归属该用户文件，
        // 贴图替换另存时以此名作为默认文件名（改回原名即可直接替换游戏文件）
        for (i in before until assetsFileList.size) {
            assetsFileList[i].rootFileName = fileName
        }
    }

    /**
     * 批量加载收尾：构造全部对象并做一次全局关联（与 loadFile 单文件行为一致）。
     *
     * [memoryGuard] 见 [readAssets]。返回因内存不足而未构造对象的文件数
     * （0 = 全部构造完成）。
     */
    fun finishLoading(memoryGuard: (() -> Boolean)? = null): Int {
        val remaining = readAssets(memoryGuard)
        processAssets()
        return remaining
    }

    /**
     * 当前解析结果占用的估算内存（字节）：
     * 序列化文件字节 + 资源文件（.resS 等）字节。
     * 文件夹批量加载时用于软上限判断，防止无限累积导致 OOM。
     */
    fun retainedBytes(): Long =
        assetsFileList.sumOf { it.fileBytes.size.toLong() } +
            resourceFileReaders.values.sumOf { it.size.toLong() }

    /** C# LoadFile(FileReader)：按嗅探出的类型分发 */
    private fun loadFile(reader: FileReader, unityVersion: String?, originalPath: String?, chain: List<SourceRef> = emptyList()) {
        var effective = reader
        // AB管理器加密包（UnityKH*FS）：魔数精确匹配，任何容器层级（根文件/zip 内/解压后）
        // 均可解密。解密 blocksInfo 后以原始压缩块重建标准 UnityFS，交由原生 BundleFile 解析。
        // 非 KH 文件 decode 直接返回 null，标准加载路径零影响；KH 数据损坏时抛出明确异常。
        val kh = KhBundleDecoder.decode(reader.data)
        if (kh != null) {
            lastUnwrapNote = kh.note
            effective = FileReader(reader.fullPath, kh.unityFs)
        } else if (chain.isEmpty()) {
            // 加密/加壳 bundle 自动解包：头部垃圾字节剥离 + 单字节 XOR 解密
            //（魔数位于偏移 0 的标准文件不受影响，autoUnwrap 直接返回 null）
            try {
                val unwrapped = EncryptedBundleDecoder.autoUnwrap(reader.data)
                if (unwrapped != null) {
                    val key = EncryptedBundleDecoder.detectXorKey(reader.data)
                    lastUnwrapNote = if (key != null) {
                        "检测到 XOR 加密（key=0x%02X），已自动解密".format(key)
                    } else {
                        "检测到非标准文件头，已自动定位 Unity 数据（偏移 ${reader.data.size - unwrapped.data.size} 字节）"
                    }
                    effective = FileReader(reader.fullPath, unwrapped.data)
                    if (unwrapped.fallbacks.isNotEmpty()) {
                        pendingUnwrapFallbacks[reader.fullPath] = unwrapped.fallbacks
                    }
                } else {
                    lastUnwrapNote = null
                }
            } catch (_: Exception) {
                // 探测失败按原文件继续
            }
        }
        when (effective.fileType) {
            FileType.AssetsFile -> loadAssetsFile(effective, unityVersion, chain)
            FileType.BundleFile -> loadBundleFile(effective, originalPath, chain, reader.data)
            FileType.WebFile -> loadWebFile(effective, chain)
            FileType.GZipFile -> loadFile(
                FileReader(effective.fullPath, Gzip.decompress(effective.data)),
                unityVersion, originalPath, chain
            )
            FileType.BrotliFile -> loadFile(
                FileReader(effective.fullPath, Brotli.decompress(effective.data)),
                unityVersion, originalPath, chain
            )
            FileType.ZipFile -> loadZipFile(effective, unityVersion, originalPath, chain)
            else -> {
                // 旧版本另存兼容：SerializedFile 头部 fileSize 字段被误按小端写入
                //（应为大端）时，嗅探会误判为 ResourceFile，此处尝试修复后按资产文件加载
                val fixed = tryFixSwappedSerializedHeader(effective.data)
                if (fixed != null) {
                    loadAssetsFile(FileReader(effective.fullPath, fixed), unityVersion, chain)
                } else {
                    // ResourceFile / TextFile：作为资源文件登记（.resS 等）
                    resourceFileReaders[effective.fileName] = effective.data
                }
            }
        }
    }

    // ============================ 旧版另存文件兼容 ============================

    /**
     * 修复旧版本（<= v1.3.3）贴图替换另存文件中的 SerializedFile 头部：
     * fileSize 字段被误按文件元数据字节序（通常小端）写入，而头部固定大端，
     * 导致重新加载时嗅探失败（条目被当作资源文件，资产列表为空且无报错）。
     *
     * 判定条件（高度特异，避免误伤普通资源文件）：
     * - version @8(BE) 在 9..40 之间
     * - fileSize 字段按小端读取恰好等于文件实际长度，且大端读取不等于（证明仅字节序错）
     * - dataOffset 字段合法
     * @return 修复后的数据副本；非此症状返回 null
     */
    private fun tryFixSwappedSerializedHeader(data: ByteArray): ByteArray? {
        if (data.size < 48) return null
        fun be32(off: Int): Int =
            ((data[off].toInt() and 0xFF) shl 24) or ((data[off + 1].toInt() and 0xFF) shl 16) or
                ((data[off + 2].toInt() and 0xFF) shl 8) or (data[off + 3].toInt() and 0xFF)
        fun le32(off: Int): Int =
            (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8) or
                ((data[off + 2].toInt() and 0xFF) shl 16) or ((data[off + 3].toInt() and 0xFF) shl 24)

        val version = be32(8)
        if (version < 9 || version > 40) return null

        val fixed = data.copyOf()
        if (version >= 22) {
            // v22+ 大文件头：fileSize(i64) @24，dataOffset(i64) @32
            var leSize = 0L
            for (i in 7 downTo 0) leSize = (leSize shl 8) or (data[24 + i].toLong() and 0xFF)
            var beSize = 0L
            for (i in 0 until 8) beSize = (beSize shl 8) or (data[24 + i].toLong() and 0xFF)
            var dataOffset = 0L
            for (i in 0 until 8) dataOffset = (dataOffset shl 8) or (data[32 + i].toLong() and 0xFF)
            if (beSize == data.size.toLong()) return null   // 字节序本来就正确
            if (leSize != data.size.toLong()) return null   // 非小端误写症状
            if (dataOffset <= 0 || dataOffset > data.size) return null
            for (i in 0 until 8) {
                fixed[24 + i] = ((data.size.toLong() ushr (8 * (7 - i))) and 0xFF).toByte()
            }
        } else {
            // v9..v21：fileSize(u32) @4，dataOffset(u32) @12
            val beSize = be32(4)
            val leSize = le32(4)
            val dataOffset = be32(12)
            if (beSize == data.size) return null            // 字节序本来就正确
            if (leSize != data.size) return null            // 非小端误写症状
            if (dataOffset <= 0 || dataOffset > data.size) return null
            fixed[4] = ((data.size ushr 24) and 0xFF).toByte()
            fixed[5] = ((data.size ushr 16) and 0xFF).toByte()
            fixed[6] = ((data.size ushr 8) and 0xFF).toByte()
            fixed[7] = (data.size and 0xFF).toByte()
        }
        return fixed
    }

    /**
     * bundle/web 条目加载辅助：返回可作为 SerializedFile 加载的数据。
     * 正常嗅探为 AssetsFile 时原样返回；否则尝试旧版另存文件的字节序修复。
     * @return 可加载的数据；null 表示该条目应作为资源文件登记
     */
    private fun assetsDataOrNull(data: ByteArray): ByteArray? {
        if (FileTypeDetector.detect("", data) == FileType.AssetsFile) return data
        return tryFixSwappedSerializedHeader(data)
    }

    // ============================ 加载分支 ============================

    /** C# LoadAssetsFile（直接打开的 .assets，外部 .resS 走 externalResourceOpener） */
    private fun loadAssetsFile(reader: FileReader, unityVersion: String?, chain: List<SourceRef> = emptyList()) {
        if (assetsFileListHash.contains(reader.fileName.lowercase())) {
            return // Skipping（C#: Logger.Info($"Skipping {reader.FullPath}")）
        }
        try {
            val assetsFile = SerializedFile(reader.data, reader.fileName, reader.fullPath, this, unityVersion)
            assetsFile.originalPath = reader.fullPath
            assetsFile.sourceChain = chain
            checkStrippedVersion(assetsFile)
            assetsFileList.add(assetsFile)
            assetsFileListHash.add(assetsFile.fileName.lowercase())
            // C# 版此处会扫描磁盘寻找外部 .resS；内存模型改为回调提供
            registerExternalResources(assetsFile)
        } catch (e: Exception) {
            errors.add("Error while reading assets file ${reader.fullPath}: $e")
        }
    }

    /** C# LoadAssetsFromMemory（bundle/web 内嵌 assets 条目） */
    private fun loadAssetsFromMemory(reader: FileReader, originalPath: String, unityVersion: String?, chain: List<SourceRef> = emptyList()) {
        if (assetsFileListHash.contains(reader.fileName.lowercase())) {
            return // Skipping
        }
        try {
            val assetsFile = SerializedFile(reader.data, reader.fileName, reader.fullPath, this, unityVersion)
            assetsFile.originalPath = originalPath
            assetsFile.sourceChain = chain
            checkStrippedVersion(assetsFile)
            assetsFileList.add(assetsFile)
            assetsFileListHash.add(assetsFile.fileName.lowercase())
        } catch (e: Exception) {
            errors.add("Error while reading assets file ${reader.fullPath} from ${getFileName(originalPath)}: $e")
            // C#: 解析失败时把数据当资源文件登记
            resourceFileReaders[reader.fileName] = reader.data
        }
    }

    /** C# LoadBundleFile：UnityFS/UnityWeb/UnityRaw 容器，内部条目递归加载 */
    private fun loadBundleFile(
        reader: FileReader,
        originalPath: String?,
        chain: List<SourceRef> = emptyList(),
        originalData: ByteArray? = null
    ) {
        try {
            val bundleFile = BundleFile(reader.data, reader.fileName)
            for (file in bundleFile.fileList) {
                val subChain = chain + SourceRef.Bundle(BundleSourceRef(bundleFile, file.path))
                // assetsDataOrNull：正常嗅探 + 旧版另存文件字节序修复（详见其注释）
                val assetsData = assetsDataOrNull(file.data)
                if (assetsData != null) {
                    loadAssetsFromMemory(
                        FileReader(file.fileName, assetsData),
                        originalPath ?: reader.fullPath,
                        bundleFile.m_Header.unityRevision,
                        subChain
                    )
                } else {
                    resourceFileReaders[file.fileName] = file.data
                }
            }
            pendingUnwrapFallbacks.remove(reader.fullPath)
        } catch (e: Exception) {
            // 首选解密失败：依次重试备选（仅头部 XOR 的不同边界候选）
            val fallbacks = pendingUnwrapFallbacks.remove(reader.fullPath)
            if (fallbacks != null && originalData != null) {
                for (candidate in fallbacks) {
                    try {
                        val bundleFile = BundleFile(candidate, reader.fileName)
                        for (file in bundleFile.fileList) {
                            val subChain =
                                chain + SourceRef.Bundle(BundleSourceRef(bundleFile, file.path))
                            val assetsData = assetsDataOrNull(file.data)
                            if (assetsData != null) {
                                loadAssetsFromMemory(
                                    FileReader(file.fileName, assetsData),
                                    originalPath ?: reader.fullPath,
                                    bundleFile.m_Header.unityRevision,
                                    subChain
                                )
                            } else {
                                resourceFileReaders[file.fileName] = file.data
                            }
                        }
                        return
                    } catch (_: Exception) {
                        // 继续下一个候选
                    }
                }
            }
            var str = "Error while reading bundle file ${reader.fullPath}"
            if (originalPath != null) {
                str += " from ${getFileName(originalPath)}"
            }
            errors.add("$str: $e")
        }
    }

    /** C# LoadWebFile：UnityWebData 嵌套 web 文件 */
    private fun loadWebFile(reader: FileReader, chain: List<SourceRef> = emptyList()) {
        try {
            val webFile = WebFile(reader.data)
            for (file in webFile.fileList) {
                val subChain = chain + SourceRef.Web(WebSourceRef(webFile, file.path))
                // assetsDataOrNull：正常嗅探 + 旧版另存文件字节序修复（详见其注释）
                val assetsData = assetsDataOrNull(file.data)
                when {
                    assetsData != null -> loadAssetsFromMemory(
                        FileReader(file.fileName, assetsData), reader.fullPath, null, subChain
                    )
                    FileTypeDetector.detect(file.fileName, file.data) == FileType.BundleFile ->
                        loadBundleFile(FileReader(file.fileName, file.data), reader.fullPath, subChain)
                    FileTypeDetector.detect(file.fileName, file.data) == FileType.WebFile ->
                        loadWebFile(FileReader(file.fileName, file.data), subChain)
                    else -> resourceFileReaders[file.fileName] = file.data
                }
            }
        } catch (e: Exception) {
            errors.add("Error while reading web file ${reader.fullPath}: $e")
        }
    }

    /** C# LoadZipFile：zip 容器（含 .split 分片合并） */
    private fun loadZipFile(reader: FileReader, unityVersion: String?, originalPath: String?, chain: List<SourceRef> = emptyList()) {
        try {
            val entries = LinkedHashMap<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(reader.data)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        entries[entry.name] = zip.readBytes()
                    }
                    entry = zip.nextEntry
                }
            }

            // 登记全部条目名并收集分片文件（C#: importFilesHash + splitFiles）
            val splitBasePaths = LinkedHashSet<String>()
            for (name in entries.keys) {
                val entryName = getFileName(name)
                if (entryName.contains(".split")) {
                    val basePath = name.substringBeforeLast(".") // 去掉 .splitN
                    splitBasePaths.add(basePath)
                    importFilesHash.add(entryName.substringBeforeLast(".").lowercase())
                } else {
                    importFilesHash.add(entryName.lowercase())
                }
            }

            // 合并分片后加载
            for (basePath in splitBasePaths) {
                try {
                    val merged = ByteArrayOutputStream()
                    var i = 0
                    while (true) {
                        val part = entries["$basePath.split$i"] ?: break
                        merged.write(part)
                        i++
                    }
                    if (i > 0) {
                        val subChain = chain + SourceRef.Zip(ZipSourceRef(entries, basePath))
                        loadFile(FileReader(basePath, merged.toByteArray()), unityVersion, originalPath ?: reader.fullPath, subChain)
                    }
                } catch (e: Exception) {
                    errors.add("Error while reading zip split file $basePath: $e")
                }
            }

            // 加载其余条目
            for ((name, data) in entries) {
                try {
                    val dummyPath = if (name.contains('/') || name.contains('\\')) {
                        "${reader.fullPath}/${name}"
                    } else {
                        name
                    }
                    val entryReader = FileReader(dummyPath, data)
                    val subChain = chain + SourceRef.Zip(ZipSourceRef(entries, name))
                    loadFile(entryReader, unityVersion, originalPath ?: reader.fullPath, subChain)
                    if (entryReader.fileType == FileType.ResourceFile) {
                        val entryName = getFileName(name)
                        if (!resourceFileReaders.containsKey(entryName)) {
                            resourceFileReaders[entryName] = data
                        }
                    }
                } catch (e: Exception) {
                    errors.add("Error while reading zip entry $name: $e")
                }
            }
        } catch (e: Exception) {
            errors.add("Error while reading zip file ${reader.fileName}: $e")
        }
    }

    /** 直接加载 .assets 时，通过 externalResourceOpener 预取外部资源并缓存 */
    private fun registerExternalResources(assetsFile: SerializedFile) {
        val opener = externalResourceOpener ?: return
        for (sharedFile in assetsFile.m_Externals) {
            val sharedFileName = sharedFile.fileName
            if (sharedFileName.isEmpty()) continue
            if (getResourceFileData(sharedFileName) != null) continue // 已在内存
            val data = try {
                opener(sharedFileName)
            } catch (e: Exception) {
                null
            } ?: continue
            resourceFileReaders[sharedFileName] = data
        }
    }

    /**
     * 按文件名查找资源数据（忽略大小写）：优先 resourceFileReaders，
     * 未命中时调用 externalResourceOpener 并缓存结果。
     */
    fun getResourceFileData(fileName: String): ByteArray? {
        resourceFileReaders[fileName]?.let { return it }
        resourceFileReaders.entries.firstOrNull { it.key.equals(fileName, ignoreCase = true) }?.let { return it.value }
        val opener = externalResourceOpener ?: return null
        val data = try {
            opener(fileName)
        } catch (e: Exception) {
            null
        } ?: return null
        resourceFileReaders[fileName] = data
        return data
    }

    // ============================ 版本处理 ============================

    /** C# CheckStrippedVersion */
    fun checkStrippedVersion(assetsFile: SerializedFile) {
        if (assetsFile.isVersionStripped && specifyUnityVersion.isNullOrEmpty()) {
            throw Exception("The Unity version has been stripped, please set the version in the options")
        }
        val specify = specifyUnityVersion
        if (!specify.isNullOrEmpty()) {
            assetsFile.setVersion(specify)
        }
    }

    // ============================ 对象构造 ============================

    /**
     * C# ReadAssets：遍历所有 m_Objects 构造具体类对象。
     *
     * [memoryGuard] 每构造完一个文件的全部对象后调用一次，返回 true 表示堆内存
     * 已达安全线，立即停止构造后续文件的对象（防止数千文件的对象图撑爆应用堆，
     * ColorOS 等厂商系统在堆满时其监控线程自身分配即失败导致闪退）。
     * 未构造的文件保留 m_Objects 表，后续可再调 readAssets 续构造。
     *
     * @return 因内存停止时剩余未构造的文件数（0 = 全部完成）
     */
    fun readAssets(memoryGuard: (() -> Boolean)? = null): Int {
        for ((index, assetsFile) in assetsFileList.withIndex()) {
            // 同一文件只构造一次（loadFile 可被多次调用）
            if (readObjectFiles.contains(assetsFile)) continue
            if (memoryGuard != null && memoryGuard()) {
                // 剩余（含当前）文件未构造；readObjectFiles 未加入，续调可恢复
                return assetsFileList.size - index
            }
            readObjectFiles.add(assetsFile)
            for (objectInfo in assetsFile.m_Objects) {
                val objectReader = ObjectReader(assetsFile.fileBytes, assetsFile, objectInfo)
                try {
                    val obj: UnityObject = when (objectReader.type) {
                        ClassIDType.Animation -> Animation(objectReader)
                        ClassIDType.AnimationClip -> AnimationClip(objectReader)
                        ClassIDType.Animator -> Animator(objectReader)
                        ClassIDType.AnimatorController -> AnimatorController(objectReader)
                        ClassIDType.AnimatorOverrideController -> AnimatorOverrideController(objectReader)
                        ClassIDType.AssetBundle -> AssetBundle(objectReader)
                        ClassIDType.AudioClip -> AudioClip(objectReader)
                        ClassIDType.Avatar -> Avatar(objectReader)
                        ClassIDType.Font -> Font(objectReader)
                        ClassIDType.GameObject -> GameObject(objectReader)
                        ClassIDType.Material -> Material(objectReader)
                        ClassIDType.Mesh -> Mesh(objectReader)
                        ClassIDType.MeshFilter -> MeshFilter(objectReader)
                        ClassIDType.MeshRenderer -> MeshRenderer(objectReader)
                        ClassIDType.MonoBehaviour -> MonoBehaviour(objectReader)
                        ClassIDType.MonoScript -> MonoScript(objectReader)
                        ClassIDType.MovieTexture -> MovieTexture(objectReader)
                        ClassIDType.PlayerSettings -> PlayerSettings(objectReader)
                        ClassIDType.RectTransform -> RectTransform(objectReader)
                        ClassIDType.Shader -> Shader(objectReader)
                        ClassIDType.SkinnedMeshRenderer -> SkinnedMeshRenderer(objectReader)
                        ClassIDType.Sprite -> Sprite(objectReader)
                        ClassIDType.SpriteAtlas -> SpriteAtlas(objectReader)
                        ClassIDType.TextAsset -> TextAsset(objectReader)
                        ClassIDType.Texture2D -> Texture2D(objectReader)
                        ClassIDType.Transform -> Transform(objectReader)
                        ClassIDType.VideoClip -> VideoClip(objectReader)
                        ClassIDType.ResourceManager -> ResourceManager(objectReader)
                        else -> UnityObject(objectReader)
                    }
                    assetsFile.addObject(obj)
                } catch (e: Throwable) {
                    // Throwable 而非 Exception：OutOfMemoryError 等 Error 同样捕获，
                    // 单个对象数据异常时跳过它并继续加载其余资产，避免整个应用闪退
                    errors.add(
                        buildString {
                            appendLine("Unable to load object")
                            appendLine("Assets ${assetsFile.fileName}")
                            appendLine("Path ${assetsFile.originalPath}")
                            appendLine("Type ${objectReader.type}")
                            appendLine("PathID ${objectInfo.m_PathID}")
                            append(e.toString())
                        }
                    )
                }
            }
        }
        return 0
    }

    /**
     * 剥离已构造完成且不含 MonoBehaviour 文件的类型树（TypeTree）。
     *
     * 类型树是解析结构的内存大头（数千节点 × 字符串）；对象构造用的是各类型
     * 手写解析器，构造完成后类型树即无用。含 MonoBehaviour 的文件保留
     * （详情页预览与导出 TypeTree 转储需要）；未构造完成的文件保留
     * （续调构造时 MonoBehaviour 分支可能引用）。
     *
     * @return 释放的类型树节点总数（统计用）
     */
    fun stripTypeTrees(): Int {
        var stripped = 0
        for (assetsFile in assetsFileList) {
            if (assetsFile !in readObjectFiles) continue
            val hasMono = assetsFile.objects.any { it is MonoBehaviour }
            if (hasMono) continue
            for (t in assetsFile.m_Types) {
                val tree = t.m_Type ?: continue
                stripped += tree.m_Nodes.size
                tree.m_Nodes = ArrayList()
                tree.m_StringBuffer = null
                t.m_Type = null
            }
        }
        return stripped
    }

    /** C# ProcessAssets：GameObject 组件关联 + SpriteAtlas 关联 */
    fun processAssets() {
        for (assetsFile in assetsFileList) {
            for (obj in assetsFile.objects) {
                if (obj is GameObject) {
                    for (pptr in obj.m_Components) {
                        val m_Component = pptr.tryGetAny() ?: continue
                        when (m_Component) {
                            is Transform -> obj.m_Transform = m_Component
                            is MeshRenderer -> obj.m_MeshRenderer = m_Component
                            is MeshFilter -> obj.m_MeshFilter = m_Component
                            is SkinnedMeshRenderer -> obj.m_SkinnedMeshRenderer = m_Component
                            is Animator -> obj.m_Animator = m_Component
                            is Animation -> obj.m_Animation = m_Component
                        }
                    }
                } else if (obj is SpriteAtlas) {
                    for (m_PackedSprite in obj.m_PackedSprites) {
                        val m_Sprite: Sprite = m_PackedSprite.tryGet() ?: continue
                        // Kotlin 移植版 m_SpriteAtlas 为可空 PPtr?（老版本文件无该字段）
                        val spriteAtlasPtr = m_Sprite.m_SpriteAtlas ?: continue
                        if (spriteAtlasPtr.isNull) {
                            spriteAtlasPtr.set(obj)
                        } else {
                            val m_SpriteAtlaOld: SpriteAtlas? = spriteAtlasPtr.tryGet()
                            if (m_SpriteAtlaOld?.m_IsVariant == true) {
                                spriteAtlasPtr.set(obj)
                            }
                        }
                    }
                }
            }
        }
    }

    // ============================ 查询/清理 ============================

    /** C# FindAssetsFile（忽略大小写匹配文件名） */
    fun findAssetsFile(name: String): SerializedFile? {
        return assetsFileList.firstOrNull { it.fileName.equals(name, ignoreCase = true) }
    }

    /** C# Clear */
    fun clear() {
        for (assetsFile in assetsFileList) {
            assetsFile.objects.clear()
            assetsFile.objectsDic.clear()
        }
        assetsFileList.clear()
        resourceFileReaders.clear()
        assetsFileListHash.clear()
        importFilesHash.clear()
        readObjectFiles.clear()
        errors.clear()
        pendingUnwrapFallbacks.clear()
        lastUnwrapNote = null
    }
}

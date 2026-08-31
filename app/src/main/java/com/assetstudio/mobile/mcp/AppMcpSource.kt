package com.assetstudio.mobile.mcp

/*
 * McpAssetSource 的 Android 侧实现：基于 core.manager.AssetsManager 暴露已加载资源。
 *
 * 约定：
 * - 类型名 = 对象类 simpleName（Texture2D / Sprite / TextAsset / Mesh / MonoBehaviour ...）
 * - 名称   = UnityObject.displayName
 * - container 路径 = AssetBundle / ResourceManager 对象的 m_Container 反查（找不到为空串）
 *   （对应 C# AssetStudio 的 containerDic；SerializedFile 本身没有 m_Container 字段）
 * - 贴图预览：AssetExporter.decodeTexture/decodeSprite 解码 → 按最长边缩放 → PNG 压缩，
 *   只返回 ByteArray（base64 编码由 core 层完成）
 * - 导出：png / txt / obj / raw 四种格式，写入 exportDir，文件名冲突加 -2/-3 后缀
 */

import android.graphics.Bitmap
import com.assetstudio.mobile.core.classes.AssetBundle
import com.assetstudio.mobile.core.classes.AudioClip
import com.assetstudio.mobile.core.classes.Material
import com.assetstudio.mobile.core.classes.Mesh
import com.assetstudio.mobile.core.classes.MeshFilter
import com.assetstudio.mobile.core.classes.PPtr
import com.assetstudio.mobile.core.classes.ResourceManager
import com.assetstudio.mobile.core.classes.Shader
import com.assetstudio.mobile.core.classes.SkinnedMeshRenderer
import com.assetstudio.mobile.core.classes.Sprite
import com.assetstudio.mobile.core.classes.TextAsset
import com.assetstudio.mobile.core.classes.Texture2D
import com.assetstudio.mobile.core.classes.UnityObject
import com.assetstudio.mobile.core.manager.AssetsManager
import com.assetstudio.mobile.export.AssetExporter
import com.assetstudio.mobile.export.ObjExporter
import com.assetstudio.mobile.render.RenderDumper
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max

class AppMcpSource(
    private val mgr: AssetsManager,
    private val exportDir: File
) : McpAssetSource {

    // ============================ 查询辅助 ============================

    /** 对象类型名：类 simpleName（与 UI 列表/类型筛选一致的口径） */
    private fun typeName(obj: UnityObject): String = obj::class.simpleName ?: obj.type.name

    /**
     * pathID → 容器路径（如 assets/textures/testtex.png）。
     * 数据来源是 AssetBundle / ResourceManager 对象里的 m_Container 表。
     */
    private fun containerMap(): Map<Long, String> {
        val map = HashMap<Long, String>()
        for (file in mgr.assetsFileList) {
            for (obj in file.objects) {
                when (obj) {
                    is AssetBundle -> for ((path, info) in obj.m_Container) {
                        map[info.asset.m_PathID] = path
                    }
                    is ResourceManager -> for ((path, ptr) in obj.m_Container) {
                        map[ptr.m_PathID] = path
                    }
                }
            }
        }
        return map
    }

    /**
     * 按 pathID 查找唯一对象：遍历 assetsFileList，
     * 匹配 objectsDic[pathId] 且（fileName 为 null/空 或 assetsFile.fileName.endsWith(fileName)）。
     */
    private fun findByPathId(pathId: Long, fileName: String?): UnityObject? {
        val name = fileName?.takeIf { it.isNotEmpty() } ?: return findFirst(pathId)
        for (file in mgr.assetsFileList) {
            if (!file.fileName.endsWith(name)) continue
            val obj = file.objectsDic[pathId] ?: continue
            return obj
        }
        return null
    }

    private fun findFirst(pathId: Long): UnityObject? {
        for (file in mgr.assetsFileList) {
            file.objectsDic[pathId]?.let { return it }
        }
        return null
    }

    // ============================ McpAssetSource ============================

    override fun loadedFiles(): List<McpFileSummary> =
        mgr.assetsFileList.map { McpFileSummary(it.fileName, it.unityVersion, it.m_Objects.size) }

    override fun listAssets(
        type: String?,
        keyword: String?,
        offset: Int,
        limit: Int
    ): McpAssetPage {
        val containers = containerMap()
        val typeFilter = type?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()
        val kw = keyword?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()

        val entries = ArrayList<McpAssetEntry>()
        for (file in mgr.assetsFileList) {
            val fileName = file.fileName
            for (obj in file.objects) {
                val objType = typeName(obj)
                if (typeFilter != null && !objType.lowercase().equals(typeFilter)) continue
                val name = obj.displayName
                val container = containers[obj.m_PathID] ?: ""
                if (kw != null) {
                    if (!name.lowercase().contains(kw) && !container.lowercase().contains(kw)) continue
                }
                entries.add(McpAssetEntry(obj.m_PathID, name, objType, obj.byteSize, fileName, container))
            }
        }

        val total = entries.size
        val safeOffset = offset.coerceIn(0, total)
        val safeLimit = limit.coerceAtLeast(1)
        val end = (safeOffset + safeLimit).coerceAtMost(total)
        val items = if (safeOffset < end) entries.subList(safeOffset, end).toList() else emptyList()
        return McpAssetPage(items, total, offset, limit)
    }

    override fun searchAssets(keyword: String, limit: Int): List<McpAssetEntry> =
        listAssets(null, keyword, 0, limit.coerceAtLeast(1)).items

    override fun assetDetail(pathId: Long, fileName: String?): Map<String, Any?>? {
        val obj = findByPathId(pathId, fileName) ?: return null
        val containers = containerMap()

        val detail = LinkedHashMap<String, Any?>()
        detail["type"] = typeName(obj)
        detail["name"] = obj.displayName
        detail["pathId"] = obj.m_PathID
        detail["byteSize"] = obj.byteSize
        detail["fileName"] = obj.assetsFile.fileName
        detail["container"] = containers[obj.m_PathID] ?: ""

        when (obj) {
            is Texture2D -> {
                detail["width"] = obj.m_Width
                detail["height"] = obj.m_Height
                detail["format"] = obj.m_TextureFormat.name
                detail["mipCount"] = mipCountOf(obj)
                val stream = obj.m_StreamData
                if (stream != null && stream.path.isNotEmpty()) {
                    detail["streamed"] = true
                    detail["streamPath"] = stream.path
                    detail["streamSize"] = stream.size
                } else {
                    detail["streamed"] = false
                    detail["dataSize"] =
                        if (obj.m_CompleteImageSize > 0) obj.m_CompleteImageSize.toLong() else obj.byteSize
                }
            }
            is Mesh -> {
                detail["vertexCount"] = obj.m_VertexCount
                detail["subMeshCount"] = obj.m_SubMeshes.size
                detail["triangleCount"] = triangleCountOf(obj)
            }
            is SkinnedMeshRenderer -> {
                detail["boneCount"] = obj.m_Bones.size
                detail["materialCount"] = obj.m_Materials.size
                obj.m_BlendShapeWeights?.let { detail["blendShapeWeightCount"] = it.size }
                putMeshRef(detail, obj.m_Mesh)
            }
            is MeshFilter -> putMeshRef(detail, obj.m_Mesh)
            is TextAsset -> {
                val text = obj.getScriptText()
                detail["length"] = text.length
                detail["summary"] = text.take(200)
            }
            is AudioClip -> {
                detail["frequency"] = obj.m_Frequency
                detail["channels"] = obj.m_Channels
                detail["length"] = obj.m_Length
            }
            else -> {
                detail["classIdType"] = obj.type.name
            }
        }
        return detail
    }

    override fun textContent(pathId: Long, fileName: String?): String? {
        val obj = findByPathId(pathId, fileName) ?: return null
        return when (obj) {
            is TextAsset -> obj.getScriptText()
            else -> null
        }
    }

    override fun renderDump(pathId: Long, fileName: String?): String? {
        val obj = findByPathId(pathId, fileName) ?: return null
        return when (obj) {
            is Material -> RenderDumper.dumpMaterial(obj)
            is Shader -> RenderDumper.dumpShader(obj)
            is SkinnedMeshRenderer -> RenderDumper.dumpSkinnedMeshRenderer(obj)
            is MeshFilter -> RenderDumper.dumpMeshFilter(obj)
            else -> null
        }
    }

    override fun texturePreviewPng(pathId: Long, maxSize: Int): ByteArray? {
        val obj = findByPathId(pathId, null) ?: return null
        val bitmap = when (obj) {
            is Texture2D -> AssetExporter.decodeTexture(obj)
            is Sprite -> AssetExporter.decodeSprite(obj)
            else -> return null
        } ?: return null

        val target = maxSize.coerceAtLeast(1)
        val scaled = if (bitmap.width > target || bitmap.height > target) {
            val scale = target.toFloat() / max(bitmap.width, bitmap.height)
            val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, w, h, true) ?: bitmap
        } else {
            bitmap
        }
        return try {
            val out = ByteArrayOutputStream()
            if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, out)) null else out.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    override fun textureStats(): Map<String, Any?> {
        val textures = ArrayList<Texture2D>()
        for (file in mgr.assetsFileList) {
            for (obj in file.objects) {
                if (obj is Texture2D) textures.add(obj)
            }
        }

        class FormatAcc(var count: Int = 0, var diskBytes: Long = 0, var vramBytes: Long = 0)

        val formatAcc = LinkedHashMap<String, FormatAcc>()
        val resolutionAcc = LinkedHashMap<String, Int>()
        var inlineCount = 0
        var streamedCount = 0
        var totalDiskBytes = 0L
        var totalVramBytes = 0L
        val perTexture = ArrayList<Map<String, Any?>>()

        for (tex in textures) {
            val disk = textureDiskBytes(tex)
            val vram = vramBytesOf(tex)
            val format = tex.m_TextureFormat.name

            val acc = formatAcc.getOrPut(format) { FormatAcc() }
            acc.count++
            acc.diskBytes += disk
            acc.vramBytes += vram

            val resolution = "${tex.m_Width}x${tex.m_Height}"
            resolutionAcc[resolution] = (resolutionAcc[resolution] ?: 0) + 1

            if (isStreamed(tex)) streamedCount++ else inlineCount++
            totalDiskBytes += disk
            totalVramBytes += vram

            perTexture.add(
                linkedMapOf(
                    "name" to tex.displayName,
                    "pathId" to tex.m_PathID,
                    "fileName" to tex.assetsFile.fileName,
                    "width" to tex.m_Width,
                    "height" to tex.m_Height,
                    "format" to format,
                    "diskBytes" to disk,
                    "vramBytes" to vram
                )
            )
        }

        val formatDistribution = LinkedHashMap<String, Any?>()
        for ((format, acc) in formatAcc.entries.sortedByDescending { it.value.count }) {
            formatDistribution[format] = linkedMapOf(
                "count" to acc.count,
                "diskBytes" to acc.diskBytes,
                "vramBytes" to acc.vramBytes
            )
        }

        val resolutionDistribution = LinkedHashMap<String, Any?>()
        for ((resolution, count) in resolutionAcc.entries.sortedByDescending { it.value }) {
            resolutionDistribution[resolution] = count
        }

        val top10 = perTexture.sortedByDescending { it["diskBytes"] as Long }.take(10)

        return linkedMapOf(
            "total" to textures.size,
            "inlineCount" to inlineCount,
            "streamedCount" to streamedCount,
            "totalDiskBytes" to totalDiskBytes,
            "totalVramBytes" to totalVramBytes,
            "formatDistribution" to formatDistribution,
            "resolutionDistribution" to resolutionDistribution,
            "top10ByDiskBytes" to top10
        )
    }

    override fun exportAsset(pathId: Long, fileName: String?, format: String): String {
        val obj = findByPathId(pathId, fileName)
            ?: throw IllegalArgumentException("pathId=$pathId 的资产不存在（fileName=${fileName ?: "任意"}）")
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            throw IllegalStateException("无法创建导出目录: ${exportDir.absolutePath}")
        }
        val baseName = safeFileName(obj.displayName)
        return when (format.lowercase()) {
            "png" -> {
                val bitmap = when (obj) {
                    is Texture2D -> AssetExporter.decodeTexture(obj)
                    is Sprite -> AssetExporter.decodeSprite(obj)
                    else -> throw IllegalArgumentException(
                        "pathId=$pathId（${typeName(obj)}）不是 Texture2D / Sprite，无法导出 PNG"
                    )
                } ?: throw IllegalArgumentException("贴图解码失败（格式不支持或数据缺失）")
                val bytes = try {
                    val out = ByteArrayOutputStream()
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        throw IllegalArgumentException("PNG 编码失败")
                    }
                    out.toByteArray()
                } catch (e: IllegalArgumentException) {
                    throw e
                } catch (e: Exception) {
                    throw IllegalArgumentException("PNG 编码失败: ${e.message}")
                }
                writeUniqueFile(baseName, "png", bytes)
            }
            "txt" -> {
                val text = when (obj) {
                    is TextAsset -> obj.getScriptText()
                    is Material -> RenderDumper.dumpMaterial(obj)
                    is Shader -> RenderDumper.dumpShader(obj)
                    is SkinnedMeshRenderer -> RenderDumper.dumpSkinnedMeshRenderer(obj)
                    is MeshFilter -> RenderDumper.dumpMeshFilter(obj)
                    else -> throw IllegalArgumentException(
                        "pathId=$pathId（${typeName(obj)}）不是 TextAsset / Material / Shader / SkinnedMeshRenderer / MeshFilter，无法导出文本"
                    )
                }
                writeUniqueFile(baseName, "txt", text.toByteArray(Charsets.UTF_8))
            }
            "obj" -> {
                // Mesh 本体，或渲染器组件（SkinnedMeshRenderer/MeshFilter）引用的网格
                val mesh = when (obj) {
                    is Mesh -> obj
                    is SkinnedMeshRenderer -> obj.m_Mesh.tryGet<Mesh>()
                    is MeshFilter -> obj.m_Mesh.tryGet<Mesh>()
                    else -> null
                } ?: throw IllegalArgumentException(
                    "pathId=$pathId（${typeName(obj)}）不是 Mesh / SkinnedMeshRenderer / MeshFilter，" +
                        "或其引用的网格指向未加载的外部文件，无法导出 OBJ"
                )
                val text = ObjExporter.write(mesh)
                writeUniqueFile(baseName, "obj", text.toByteArray(Charsets.UTF_8))
            }
            "raw" -> {
                val info = obj.assetsFile.m_Objects.firstOrNull { it.m_PathID == obj.m_PathID }
                    ?: throw IllegalArgumentException("pathId=$pathId 的对象信息缺失")
                writeUniqueFile(baseName, "dat", obj.assetsFile.getRawObjectBytes(info))
            }
            else -> throw IllegalArgumentException("不支持的导出格式: $format（可选 png/txt/obj/raw）")
        }
    }

    // ============================ 私有辅助 ============================

    /**
     * 网格引用详情（渲染器组件专用）：
     * - meshRef：原始 PPtr（fileId/pathId，跨文件引用定位用）
     * - referencedMesh：解引用成功时给出网格统计，未解析/为空时不存在该键
     */
    private fun putMeshRef(detail: LinkedHashMap<String, Any?>, ptr: PPtr) {
        detail["meshRef"] = linkedMapOf(
            "fileId" to ptr.m_FileID,
            "pathId" to ptr.m_PathID,
            "isNull" to ptr.isNull
        )
        if (ptr.isNull) return
        val mesh = ptr.tryGet<Mesh>() ?: return
        detail["referencedMesh"] = linkedMapOf(
            "pathId" to mesh.m_PathID,
            "name" to mesh.displayName,
            "fileName" to mesh.assetsFile.fileName,
            "vertexCount" to mesh.m_VertexCount,
            "subMeshCount" to mesh.m_SubMeshes.size,
            "triangleCount" to triangleCountOf(mesh)
        )
    }

    private fun mipCountOf(tex: Texture2D): Int {
        if (tex.m_MipCount > 0) return tex.m_MipCount
        if (tex.m_MipMap) {
            val maxDim = max(tex.m_Width, tex.m_Height).coerceAtLeast(1)
            return 1 + floor(log2(maxDim.toDouble())).toInt().coerceAtLeast(0)
        }
        return 1
    }

    private fun triangleCountOf(mesh: Mesh): Int {
        if (mesh.m_Indices.isNotEmpty()) return mesh.m_Indices.size / 3
        return mesh.m_SubMeshes.sumOf { (it.indexCount / 3).toInt() }
    }

    /** 磁盘字节：流式取 .resS 中的数据长度，内联取 CompleteImageSize */
    private fun textureDiskBytes(tex: Texture2D): Long {
        val stream = tex.m_StreamData
        if (stream != null && stream.path.isNotEmpty()) return stream.size
        return if (tex.m_CompleteImageSize > 0) tex.m_CompleteImageSize.toLong() else tex.byteSize
    }

    /** 显存字节估算 = 宽 * 高 * 4 * mipCount */
    private fun vramBytesOf(tex: Texture2D): Long =
        tex.m_Width.toLong() * tex.m_Height * 4L * mipCountOf(tex)

    private fun isStreamed(tex: Texture2D): Boolean {
        val stream = tex.m_StreamData ?: return false
        return stream.path.isNotEmpty()
    }

    /** 清洗成合法文件名 */
    private fun safeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_").trim()
        return cleaned.ifEmpty { "asset" }
    }

    /** 写入 exportDir，文件名冲突时追加 -2/-3 后缀，返回绝对路径 */
    private fun writeUniqueFile(baseName: String, ext: String, data: ByteArray): String {
        var file = File(exportDir, "$baseName.$ext")
        var suffix = 2
        while (file.exists()) {
            file = File(exportDir, "$baseName-$suffix.$ext")
            suffix++
        }
        file.writeBytes(data)
        return file.absolutePath
    }
}

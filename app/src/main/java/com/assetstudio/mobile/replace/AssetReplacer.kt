package com.assetstudio.mobile.replace

import android.graphics.Bitmap
import com.assetstudio.mobile.core.classes.Mesh
import com.assetstudio.mobile.core.classes.Texture2D
import com.assetstudio.mobile.core.classes.TextureFormat
import com.assetstudio.mobile.core.manager.SourceRef
import com.assetstudio.mobile.core.serialized.SerializedFile
import com.assetstudio.mobile.core.texture.PixelFlip
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/*
 * 资产替换总编排（贴图 / 模型）：
 *
 *   新数据 → 重建对象字节（Texture2DObjectRewriter / MeshObjectRewriter）
 *   → 重写 SerializedFile → 从最内层容器向外逐层回填重打包（bundle/web/zip）
 *   → 得到可保存的完整文件字节
 */
object AssetReplacer {

    class ReplaceResult(
        /** 最终容器完整字节（bundle / zip / .assets） */
        val bytes: ByteArray,
        /** 输出文件建议名 */
        val suggestedName: String,
        /** 使用的最终格式（可能回退为 RGBA32） */
        val usedFormat: TextureFormat,
        /** 产生的 mip 级数 */
        val mipCount: Int,
        /** 是否保留原格式（未回退） */
        val keptOriginalFormat: Boolean
    )

    /** 通用重打包结果（模型替换等无格式信息的场景） */
    class RepackResult(
        val bytes: ByteArray,
        val suggestedName: String
    )

    class ReplaceException(message: String) : Exception(message)

    /**
     * 替换模型（OBJ 文本 → 重写 Mesh 对象）。
     *
     * @param mesh 目标 Mesh
     * @param objText OBJ 文件全文
     */
    fun replaceMesh(mesh: Mesh, objText: String): RepackResult {
        // ---------- 1. 解析 OBJ ----------
        val objData = ObjImporter.parse(objText)

        // ---------- 2. 重建对象字节（内部含布局一致性校验） ----------
        val rewriter = MeshObjectRewriter(mesh)
        val newObjectBytes = rewriter.buildReplacement(objData)

        // ---------- 3. 重写 SerializedFile + 容器回填 ----------
        val (bytes, suggested) = repack(mesh.assetsFile, mesh.m_PathID, newObjectBytes)
        return RepackResult(bytes, suggested)
    }

    /**
     * 替换贴图。
     *
     * @param texture 目标 Texture2D
     * @param newBitmap 用户选择的新图（尺寸可与原图不同，字段同步更新）
     * @param keepFormat true 尝试按原格式编码（不可编码时回退 RGBA32）
     */
    fun replaceTexture(texture: Texture2D, newBitmap: Bitmap, keepFormat: Boolean): ReplaceResult {
        // ---------- 1. 位图 → 编码后的对象字节 ----------
        val enc = encodeTexture(texture, newBitmap, keepFormat)

        // ---------- 2. 重写 SerializedFile + 逐层回填容器 ----------
        val (current, suggested) = repack(texture.assetsFile, texture.m_PathID, enc.objectBytes)

        return ReplaceResult(
            bytes = current,
            suggestedName = suggested,
            usedFormat = enc.usedFormat,
            mipCount = enc.mipCount,
            keptOriginalFormat = enc.keptOriginal
        )
    }

    // ============================ 批量替换贴图 ============================

    /** 批量替换条目：目标贴图 + 新图片文件 */
    class BatchEntry(val texture: Texture2D, val imageFile: java.io.File, val keepFormat: Boolean = true)

    /** 单条失败记录 */
    class BatchFailure(val textureName: String, val reason: String)

    /** 单个容器的输出（一个 bundle / web / zip / .assets） */
    class BatchOutput(val name: String, val bytes: ByteArray, val textureCount: Int)

    /** 批量替换结果 */
    class BatchResult(
        /** 最终输出字节：单容器 = 该容器重打包结果；多容器 = 含全部输出的 ZIP */
        val bytes: ByteArray,
        val suggestedName: String,
        val outputs: List<BatchOutput>,
        val succeeded: List<String>,
        val failed: List<BatchFailure>,
        val keptOriginalCount: Int,
        val fallbackCount: Int
    )

    /**
     * 批量替换贴图（游戏美化包核心能力）：
     * - 单张失败不影响其余（逐条 try/catch）；
     * - 同一 SerializedFile 内的多张贴图只重写一次（一次 rewrite 写入全部 pathID）；
     * - 按最外层容器分组：同一容器只产出一份文件；共享容器的变更逐级累积，
     *   每组保留最后一次重打包结果（此时已包含本组全部修改）；
     * - 单容器输出原格式文件；多容器自动打包为 ZIP（每个文件按原文件名存放，
     *   解压后逐个替换游戏文件即可）。
     */
    fun replaceTexturesBatch(entries: List<BatchEntry>): BatchResult {
        if (entries.isEmpty()) throw ReplaceException("没有可替换的贴图")

        // ---------- 1. 逐张解码图片并编码为对象字节（图片用完即释放，控制内存峰值） ----------
        class Pending(val bytes: ByteArray, val kept: Boolean)

        val byFile = LinkedHashMap<SerializedFile, LinkedHashMap<Long, Pending>>()
        val succeeded = ArrayList<String>()
        val failed = ArrayList<BatchFailure>()
        var keptCount = 0
        var fallbackCount = 0

        for (entry in entries) {
            val name = entry.texture.displayName.ifEmpty { "#${entry.texture.m_PathID}" }
            var bmp: Bitmap? = null
            try {
                bmp = android.graphics.BitmapFactory.decodeFile(entry.imageFile.absolutePath)
                    ?: throw ReplaceException("图片读取失败：${entry.imageFile.name}")
                val enc = encodeTexture(entry.texture, bmp, entry.keepFormat)
                byFile.getOrPut(entry.texture.assetsFile) { LinkedHashMap() }
                    .put(entry.texture.m_PathID, Pending(enc.objectBytes, enc.keptOriginal))
                succeeded.add(name)
                if (enc.keptOriginal) keptCount++ else fallbackCount++
            } catch (e: OutOfMemoryError) {
                failed.add(BatchFailure(name, "内存不足（图片 ${entry.imageFile.name} 过大）"))
            } catch (e: Exception) {
                failed.add(BatchFailure(name, e.message ?: "编码失败"))
            } finally {
                bmp?.recycle()
            }
        }
        if (byFile.isEmpty()) {
            throw ReplaceException(
                "全部贴图替换失败：\n" + failed.joinToString("\n") { "  ${it.textureName}: ${it.reason}" }
            )
        }

        // ---------- 2. 每个 SerializedFile 重写一次（全部 pathID 一次写入） ----------
        val rewritten = LinkedHashMap<SerializedFile, ByteArray>()
        for ((file, pendings) in byFile) {
            try {
                rewritten[file] = SerializedFileRewriter.rewrite(
                    file,
                    pendings.mapValues { (_, p) -> p.bytes }
                )
            } catch (e: OutOfMemoryError) {
                throw ReplaceException("内存不足：${file.fileName} 重写失败，建议减少同时替换的贴图数量")
            }
        }

        // ---------- 3. 按最外层容器分组重打包 ----------
        // key = 最外层容器对象（BundleFile / WebFile / zipEntries / 无容器时文件自身）
        val groups = LinkedHashMap<Any, MutableList<SerializedFile>>()
        for (file in rewritten.keys) {
            groups.getOrPut(outermostKey(file)) { ArrayList() }.add(file)
        }

        val outputs = ArrayList<BatchOutput>()
        for (files in groups.values) {
            var lastBytes: ByteArray? = null
            var lastName = ""
            var count = 0
            for (file in files) {
                val (bytes, name) = repackRewritten(file, rewritten[file]!!)
                lastBytes = bytes
                lastName = name
                count += byFile[file]?.size ?: 0
            }
            outputs.add(BatchOutput(lastName, lastBytes!!, count))
        }

        // ---------- 4. 输出：单容器直出，多容器 ZIP ----------
        val finalBytes: ByteArray
        val finalName: String
        if (outputs.size == 1) {
            finalBytes = outputs[0].bytes
            finalName = outputs[0].name
        } else {
            val zipEntries = LinkedHashMap<String, ByteArray>()
            for (o in outputs) {
                var n = o.name
                var i = 2
                while (zipEntries.containsKey(n)) {
                    val base = n.substringBeforeLast('.', n)
                    val ext = n.substringAfterLast('.', "bin")
                    n = "${base}_$i.$ext"
                    i++
                }
                zipEntries[n] = o.bytes
            }
            finalBytes = ZipWriter.write(zipEntries)
            finalName = "batch_replace_${outputs.size}_files.zip"
        }

        return BatchResult(
            bytes = finalBytes,
            suggestedName = finalName,
            outputs = outputs,
            succeeded = succeeded,
            failed = failed,
            keptOriginalCount = keptCount,
            fallbackCount = fallbackCount
        )
    }

    /** 最外层容器标识（同一 bundle/web/zip 内的资产归为一组；无容器 = 文件自身） */
    private fun outermostKey(file: SerializedFile): Any {
        val first = file.sourceChain.firstOrNull() ?: return file
        return when (first) {
            is SourceRef.Bundle -> first.ref.bundle
            is SourceRef.Web -> first.ref.web
            is SourceRef.Zip -> first.ref.zipEntries
        }
    }

    // ============================ 单张编码（单张/批量共用） ============================

    private class EncodedTexture(
        val objectBytes: ByteArray,
        val usedFormat: TextureFormat,
        val keptOriginal: Boolean,
        val mipCount: Int
    )

    /** 位图 → 像素 → 选格式编码 → 重建 Texture2D 对象字节（字段同步更新） */
    private fun encodeTexture(texture: Texture2D, newBitmap: Bitmap, keepFormat: Boolean): EncodedTexture {
        // ---------- 1. 位图 → 像素 ----------
        val w = newBitmap.width
        val h = newBitmap.height
        if (w <= 0 || h <= 0 || w > 16384 || h > 16384) {
            throw ReplaceException("不支持的图片尺寸 ${w}x${h}")
        }
        val pixels = IntArray(w * h)
        val safeBitmap = if (newBitmap.config == Bitmap.Config.ARGB_8888) newBitmap
        else newBitmap.copy(Bitmap.Config.ARGB_8888, false)
        safeBitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        // Bitmap row 0 = 顶部；Unity 纹理数据 row 0 = 底部（OpenGL 习惯）。
        // 回写前反转行序（与解码方向互逆，保证"导出→改图→替换"闭环不颠倒）；
        // 漏掉此步替换后的贴图在游戏里上下颠倒（v1.7.7 修复）
        PixelFlip.flipVerticalInPlace(pixels, w, h)

        // ---------- 2. 选择格式并编码 ----------
        val originalFormat = texture.m_TextureFormat
        var targetFormat = originalFormat
        var kept = true
        if (!keepFormat || !TextureEncoder.isFormatEncodable(originalFormat)) {
            targetFormat = TextureFormat.RGBA32
            kept = keepFormat && TextureEncoder.isFormatEncodable(originalFormat)
        }
        // DXT 系列尺寸需 4 的倍数，不满足时回退 RGBA32（避免越界写块）
        val isBlockFormat = targetFormat == TextureFormat.DXT1 || targetFormat == TextureFormat.DXT5
        if (isBlockFormat && (w % 4 != 0 || h % 4 != 0)) {
            targetFormat = TextureFormat.RGBA32
            kept = false
        }

        // mip 级数：沿用原贴图（至少 1 级）
        val mipCount = texture.m_MipCount.coerceAtLeast(1)

        val encoded = TextureEncoder.encode(pixels, w, h, targetFormat, mipCount)
            ?: throw ReplaceException(
                TextureEncoder.lastError ?: "编码失败（${targetFormat}）"
            )

        // ---------- 3. 重建对象字节 ----------
        val rewriter = Texture2DObjectRewriter(texture)
        val newObjectBytes = rewriter.buildReplacement(w, h, targetFormat, mipCount, encoded)
        return EncodedTexture(newObjectBytes, targetFormat, kept, mipCount)
    }

    /**
     * 共享重打包：对象字节 → 重写 SerializedFile → 从最内层容器向外逐层回填。
     *
     * sourceChain 由外到内，重写从最内层（链尾）开始向外；
     * 默认名：用户最初加载的文件名（rootFileName），保证另存后改回原名即可替换游戏文件；
     * 无容器（直接打开 .assets）时回退 assetsFile 自身文件名。
     */
    private fun repack(assetsFile: SerializedFile, pathID: Long, newObjectBytes: ByteArray): Pair<ByteArray, String> =
        repackRewritten(
            assetsFile,
            SerializedFileRewriter.rewrite(assetsFile, mapOf(pathID to newObjectBytes))
        )

    /** 已重写的 SerializedFile 字节 → 从最内层容器向外逐层回填（批量路径共用） */
    private fun repackRewritten(assetsFile: SerializedFile, rewritten: ByteArray): Pair<ByteArray, String> {
        var current = rewritten

        var suggested = assetsFile.rootFileName.ifEmpty { assetsFile.fileName }.ifEmpty { "output.assets" }
        for (ref in assetsFile.sourceChain.asReversed()) {
            when (ref) {
                is SourceRef.Bundle -> {
                    val bundle = ref.ref.bundle
                    if (!BundleRepacker.canRepack(bundle)) {
                        throw BundleRepacker.RepackException(
                            "该资产位于旧版 ${bundle.m_Header.signature} Bundle 内，暂不支持重打包"
                        )
                    }
                    // 找到对应条目并替换
                    val entry = bundle.fileList.firstOrNull { it.path == ref.ref.entryPath }
                        ?: bundle.fileList.firstOrNull { it.fileName == assetsFile.fileName }
                        ?: throw ReplaceException("Bundle 条目未找到: ${ref.ref.entryPath}")
                    entry.data = current
                    current = BundleRepacker.repack(bundle)
                    suggested = assetsFile.rootFileName.ifEmpty { "repacked.bundle" }
                }
                is SourceRef.Web -> {
                    val web = ref.ref.web
                    val entry = web.fileList.firstOrNull { it.path == ref.ref.entryPath }
                        ?: throw ReplaceException("Web 条目未找到: ${ref.ref.entryPath}")
                    entry.data = current
                    current = WebFileWriter.write(web.fileList)
                    suggested = assetsFile.rootFileName.ifEmpty { "repacked.unity3d" }
                }
                is SourceRef.Zip -> {
                    ref.ref.zipEntries[ref.ref.entryName] = current
                    current = ZipWriter.write(ref.ref.zipEntries)
                    suggested = assetsFile.rootFileName.ifEmpty { "repacked.zip" }
                }
            }
        }
        return current to suggested
    }
}

/** UnityWebData1.0 容器写出（与解析端 WebFile.kt 布局对应，小端） */
object WebFileWriter {

    fun write(fileList: List<com.assetstudio.mobile.core.bundle.StreamFile>): ByteArray {
        // 头部 = "UnityWebData1.0\0" + int32 头长 + 每条目 int32 offset/size/pathLen/path
        val sig = "UnityWebData1.0".toByteArray(Charsets.UTF_8)

        // 先计算头部
        var headLen = sig.size + 1 + 4
        var dataOffset = 0L
        // 条目数据区从头部结束后开始；先按序累计
        val offsets = LongArray(fileList.size)
        for (i in fileList.indices) {
            offsets[i] = dataOffset
            dataOffset += fileList[i].data.size
        }
        fun headSize(): Int {
            var s = sig.size + 1 + 4
            for (f in fileList) {
                s += 4 + 4 + 4 + f.path.toByteArray(Charsets.UTF_8).size
            }
            return s
        }
        val headerSize = headSize()

        val out = ByteArrayOutputStream(headerSize + dataOffset.toInt())
        out.write(sig)
        out.write(0)
        writeInt32LE(out, headerSize)
        val dataBase = headerSize.toLong()
        for (i in fileList.indices) {
            val f = fileList[i]
            val pathBytes = f.path.toByteArray(Charsets.UTF_8)
            writeInt32LE(out, (dataBase + offsets[i]).toInt())
            writeInt32LE(out, f.data.size)
            writeInt32LE(out, pathBytes.size)
            out.write(pathBytes)
        }
        for (f in fileList) out.write(f.data)
        return out.toByteArray()
    }

    private fun writeInt32LE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write(v shr 8 and 0xFF)
        out.write(v shr 16 and 0xFF)
        out.write(v shr 24 and 0xFF)
    }
}

/** zip 容器写出（STORED，避免二次压缩开销与兼容性问题） */
object ZipWriter {

    fun write(entries: LinkedHashMap<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setLevel(java.util.zip.Deflater.BEST_SPEED)
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}

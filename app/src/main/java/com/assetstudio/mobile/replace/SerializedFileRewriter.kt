package com.assetstudio.mobile.replace

import com.assetstudio.mobile.core.io.EndianType
import com.assetstudio.mobile.core.serialized.ObjectInfo
import com.assetstudio.mobile.core.serialized.SerializedFile
import com.assetstudio.mobile.core.serialized.SerializedFileFormatVersion

/*
 * SerializedFile 重写器：在替换对象数据（大小可能变化）后重建整个 .assets 文件。
 *
 * 文件布局（版本 >= 9 的现代格式）：
 *   [头部][元数据（含对象表）][对齐填充][对象数据区 ...]
 * 旧格式（< 9）元数据在文件尾部，但重排公式同样适用（见下）。
 *
 * 重排算法：
 * 1. 对象按 byteStart 升序，从首个对象位置开始重拼对象数据区
 *    （每个对象按原对齐方式保持对齐，未替换对象字节原样拷贝）；
 * 2. 头部与元数据区字节保持不变，仅原位修补对象表中的
 *    byteStart/byteSize 字段（各条目宽度由版本决定，布局不变，可安全原位写）；
 * 3. 修补头部 fileSize 字段；
 * 4. 尾部（最后一个对象之后到文件末尾）原样保留。
 *
 * 由于元数据段长度不变（metadataSize 不需要改），dataOffset 也保持不变。
 */
object SerializedFileRewriter {

    class RewriteResult(
        /** 重写后的完整文件字节 */
        val bytes: ByteArray,
        /** 各对象的新绝对起始偏移（按原 m_Objects 顺序） */
        val newOffsets: LongArray,
        /** 参与重排的对象数 */
        val objectCount: Int
    )

    /**
     * @param replacements PathID -> 新对象字节（完全替换原对象数据）
     */
    fun rewrite(assetsFile: SerializedFile, replacements: Map<Long, ByteArray>): ByteArray {
        if (replacements.isEmpty()) return assetsFile.fileBytes

        val src = assetsFile.fileBytes
        val endian = assetsFile.readerEndian
        val objects = assetsFile.m_Objects
        if (objects.isEmpty()) return src

        // 1. 按原始位置排序（保留原顺序引用以便修补对象表）
        val sorted = objects.sortedBy { it.byteStart }
        val regionStart = sorted.first().byteStart.toInt()
        val lastObj = sorted.last()
        val lastObjEnd = (lastObj.byteStart + lastObj.byteSize).toInt().coerceAtMost(src.size)

        // 2. 重拼对象数据区
        val region = ArrayList<ByteArray>()
        val newAbsOffsets = HashMap<ObjectInfo, Long>()
        val dataOffset = assetsFile.header.m_DataOffset
        var cursor = regionStart.toLong()
        for (info in sorted) {
            // 对齐：保持 storedByteStart 的 16 字节对齐语义（相对 dataOffset）
            if (info.alignment > 1) {
                val rel = cursor - dataOffset
                val rem = rel % info.alignment
                if (rem != 0L) {
                    val pad = (info.alignment - rem).toInt()
                    region.add(ByteArray(pad))
                    cursor += pad
                }
            }
            newAbsOffsets[info] = cursor
            val replacement = replacements[info.m_PathID]
            if (replacement != null) {
                region.add(replacement)
                cursor += replacement.size
            } else {
                val start = info.byteStart.toInt()
                val end = (start + info.byteSize.toInt()).coerceAtMost(src.size)
                val keep = if (end > start) src.copyOfRange(start, end) else ByteArray(0)
                region.add(keep)
                cursor += keep.size
            }
        }
        val regionBytes = concat(region)

        // 3. 拼装新文件：[0, regionStart) + regionBytes + [lastObjEnd, fileSize)
        val tailLen = (src.size - lastObjEnd).coerceAtLeast(0)
        val out = ByteArray(regionStart + regionBytes.size + tailLen)
        System.arraycopy(src, 0, out, 0, regionStart)
        System.arraycopy(regionBytes, 0, out, regionStart, regionBytes.size)
        if (tailLen > 0) {
            System.arraycopy(src, lastObjEnd, out, regionStart + regionBytes.size, tailLen)
        }

        // 4. 修补对象表（byteStart / byteSize）
        for (info in objects) {
            val newStart = newAbsOffsets[info] ?: continue
            val newStored = newStart - dataOffset
            writeInt(out, info.byteStartFieldOffset, newStored, info.byteStartFieldSize, endian)
            writeInt(out, info.byteSizeFieldOffset, replacements[info.m_PathID]?.size?.toLong() ?: info.byteSize, 4, endian)
        }

        // 5. 修补头部 fileSize
        // ★ 头部固定大端（解析器以 BigEndian 读取头部四字段，与文件元数据 endian 无关）。
        //   此处曾误用文件 endian（现代 Unity 文件为小端），导致另存文件中条目的
        //   fileSize 字段字节序错误，重新加载时 isSerializedFile 嗅探失败
        //   （mFileSize 与实际长度不符）→ 条目被当作资源文件而非资产文件，
        //   表现为"加载成功但资产列表为空、无任何报错"。
        val header = assetsFile.header
        writeInt(
            out, header.fileSizeFieldOffset, out.size.toLong(),
            header.fileSizeFieldSize, EndianType.BigEndian
        )

        // 旧格式（元数据在尾部）：fileSize 改变会移动元数据位置，
        // 此时元数据随尾部字节一起整体平移，位置 = 新 fileSize - metadataSize，无需额外修补。
        return out
    }

    /** 按字段宽度与字节序写入整数 */
    private fun writeInt(buffer: ByteArray, offset: Int, value: Long, size: Int, endian: EndianType) {
        if (offset < 0) return
        if (endian == EndianType.LittleEndian) {
            for (i in 0 until size) {
                buffer[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
            }
        } else {
            for (i in 0 until size) {
                buffer[offset + size - 1 - i] = ((value ushr (8 * i)) and 0xFF).toByte()
            }
        }
    }

    private fun concat(chunks: List<ByteArray>): ByteArray {
        var total = 0
        for (c in chunks) total += c.size
        val out = ByteArray(total)
        var o = 0
        for (c in chunks) {
            System.arraycopy(c, 0, out, o, c.size)
            o += c.size
        }
        return out
    }
}

package com.assetstudio.mobile.replace

import com.assetstudio.mobile.core.bundle.ArchiveFlags
import com.assetstudio.mobile.core.bundle.BundleFile
import com.assetstudio.mobile.core.bundle.CompressionType
import com.assetstudio.mobile.core.bundle.LZ4
import com.assetstudio.mobile.core.io.EndianType
import java.io.ByteArrayOutputStream

/*
 * UnityFS 系列重打包器。
 *
 * 支持范围与解析器一致：UnityFS / UnityWeb(v6) / UnityRaw(v6)。
 * （这三者在解析端走同一条 UnityFS 代码路径，因此重打包共用本实现。）
 * 旧版 UnityWeb/UnityRaw（version < 6）多级流格式极少见于现代游戏，
 * 不支持重打包（调用方会给出明确错误提示）。
 *
 * 输出格式（与 UnityFS 规范一致，全部可被 Unity 运行时与 AssetStudio 解析）：
 *   "UnityFS\0" version unityVersion unityRevision
 *   size(i64) compressedBlocksInfoSize(u32) uncompressedBlocksInfoSize(u32) flags(u32)
 *   [v>=7: 16 字节对齐]
 *   [blocksInfo（LZ4 压缩）] 或 [BlocksInfoAtTheEnd: 位于文件尾部]
 *   [BlockInfoNeedPaddingAtStart: 16 字节对齐]
 *   [数据块（每块独立 LZ4 压缩，flags=2）]
 *
 * blocksInfo 内部：16 字节哈希（写 0，与 UABE 重打包行为一致，Unity 不校验）
 *   + 块表 + 目录表（offset/size/flags/path）。
 */
object BundleRepacker {

    /** 每个数据块的目标解压大小（Unity 默认 128KB，任意值解析端均可接受） */
    private const val BLOCK_SIZE = 128 * 1024

    /** blocksInfo / 数据块是否压缩。LZ4 编码器可用，保持压缩以控制文件体积 */
    private const val USE_COMPRESSION = true

    class RepackException(message: String) : Exception(message)

    /** 该 bundle 是否可以重打包（UnityFS 家族） */
    fun canRepack(bundle: BundleFile): Boolean {
        val sig = bundle.m_Header.signature
        if (sig == "UnityFS") return true
        if ((sig == "UnityWeb" || sig == "UnityRaw") && bundle.m_Header.version == 6L) return true
        return false
    }

    /**
     * 重打包。调用前请先把替换后的数据写回 [BundleFile.fileList] 各条目的 data。
     * @return 新 bundle 完整字节
     */
    fun repack(bundle: BundleFile): ByteArray {
        val header = bundle.m_Header
        if (!canRepack(bundle)) {
            throw RepackException(
                "旧版 ${header.signature} (v${header.version}) Bundle 不支持重打包，仅支持 UnityFS / UnityWeb v6 / UnityRaw v6"
            )
        }

        // ---------- 1. 组装全部条目数据（目录表基于此计算偏移） ----------
        var totalUncompressed = 0L
        for (f in bundle.fileList) totalUncompressed += f.data.size

        // ---------- 2. 切块压缩 ----------
        val blocks = ArrayList<Block>()
        val allData = ByteArrayOutputStream(totalUncompressed.toInt())
        for (f in bundle.fileList) allData.write(f.data)
        val dataBytes = allData.toByteArray()

        var pos = 0
        while (pos < dataBytes.size) {
            val len = minOf(BLOCK_SIZE, dataBytes.size - pos)
            val chunk = dataBytes.copyOfRange(pos, pos + len)
            val payload: ByteArray
            val flags: Int
            if (USE_COMPRESSION) {
                val compressed = LZ4.encode(chunk)
                if (compressed.size < chunk.size) {
                    payload = compressed
                    flags = CompressionType.Lz4.value
                } else {
                    payload = chunk
                    flags = CompressionType.None.value
                }
            } else {
                payload = chunk
                flags = CompressionType.None.value
            }
            blocks.add(Block(payload, chunk.size, flags))
            pos += len
        }
        if (blocks.isEmpty()) {
            // 空文件极端情况：一个空块
            blocks.add(Block(ByteArray(0), 0, CompressionType.None.value))
        }

        // ---------- 3. 目录表（条目 offset 相对数据区起点） ----------
        // 字段顺序必须与 Unity 标准（及解析器 BundleFile.readBlocksInfoAndDirectory）一致：
        // offset(i64) → size(i64) → flags(u32) → path(字符串\0)
        //（顺序写反会导致另存文件重新打开时把 path 字节读成 offset/size，
        //  解析出天文数字 size 而抛 "exceeds ByteArray limit"，无法预览）
        val directory = ByteArrayOutputStream()
        var entryOffset = 0L
        writeInt32(directory, bundle.fileList.size, EndianType.BigEndian)
        for (f in bundle.fileList) {
            writeInt64(directory, entryOffset, EndianType.BigEndian)
            writeInt64(directory, f.data.size.toLong(), EndianType.BigEndian)
            writeInt32(directory, 0, EndianType.BigEndian) // node flags
            writeString(directory, f.path)
            entryOffset += f.data.size
        }

        // ---------- 4. blocksInfo = hash(16) + 块表 + 目录表 ----------
        val blocksInfo = ByteArrayOutputStream()
        blocksInfo.write(ByteArray(16)) // uncompressedDataHash：0（UABE 同款处理）
        writeInt32(blocksInfo, blocks.size, EndianType.BigEndian)
        for (b in blocks) {
            writeInt32(blocksInfo, b.uncompressedSize, EndianType.BigEndian)
            writeInt32(blocksInfo, b.payload.size, EndianType.BigEndian)
            writeInt16(blocksInfo, b.flags, EndianType.BigEndian)
        }
        blocksInfo.write(directory.toByteArray())
        val blocksInfoUncompressed = blocksInfo.toByteArray()

        val blocksInfoPayload: ByteArray
        val infoFlags: Int
        if (USE_COMPRESSION) {
            val compressed = LZ4.encode(blocksInfoUncompressed)
            if (compressed.size < blocksInfoUncompressed.size) {
                blocksInfoPayload = compressed
                infoFlags = CompressionType.Lz4.value
            } else {
                blocksInfoPayload = blocksInfoUncompressed
                infoFlags = CompressionType.None.value
            }
        } else {
            blocksInfoPayload = blocksInfoUncompressed
            infoFlags = CompressionType.None.value
        }

        // ---------- 5. 输出布局参数 ----------
        val atTheEnd = header.flags and ArchiveFlags.BlocksInfoAtTheEnd != 0
        val padAtStart = header.flags and ArchiveFlags.BlockInfoNeedPaddingAtStart != 0
        // flags：保留原有布局位（AtTheEnd / Padding），压缩位换成 blocksInfo 实际压缩类型
        var outFlags = (header.flags and ArchiveFlags.CompressionTypeMask.inv()) or infoFlags
        // 0x40 BlocksAndDirectoryInfoCombined 与 0x80 AtTheEnd 互斥；保持原样

        val version = header.version
        val sig = if (header.signature == "UnityFS") "UnityFS" else header.signature

        // ---------- 6. 写文件 ----------
        val headerBuf = ByteArrayOutputStream()
        writeString(headerBuf, sig)
        writeInt32(headerBuf, version.toInt(), EndianType.BigEndian)
        writeString(headerBuf, header.unityVersion)
        writeString(headerBuf, header.unityRevision)
        val sizeFieldPos = headerBuf.size() // size(i64) 占位，稍后回填
        writeInt64(headerBuf, 0L, EndianType.BigEndian)
        writeInt32(headerBuf, blocksInfoPayload.size, EndianType.BigEndian)
        writeInt32(headerBuf, blocksInfoUncompressed.size, EndianType.BigEndian)
        writeInt32(headerBuf, outFlags, EndianType.BigEndian)
        // 解析端 ReadHeader：非 UnityFS 签名（UnityWeb/UnityRaw v6）在 flags 后多读 1 字节
        if (sig != "UnityFS") headerBuf.write(0)
        var headBytes = headerBuf.toByteArray()

        // v>=7：blocksInfo 前对齐 16
        if (version >= 7) headBytes = alignBuffer(headBytes, 16)

        val blocksData = ByteArrayOutputStream()
        for (b in blocks) blocksData.write(b.payload)
        val blocksBytes = blocksData.toByteArray()

        val out = ByteArrayOutputStream()
        var finalBytes: ByteArray

        if (atTheEnd) {
            // [head][blocks][pad][blocksInfo]
            var mid = headBytes + blocksBytes
            if (padAtStart) {
                // 块数据起点对齐 16（相对文件起始）
                mid = alignBufferTo(mid, headBytes.size, 16)
            }
            finalBytes = mid + blocksInfoPayload
        } else {
            // [head][blocksInfo][pad][blocks]
            if (padAtStart) {
                headBytes = alignBufferTo(headBytes + blocksInfoPayload, headBytes.size + blocksInfoPayload.size, 16)
            } else {
                headBytes += blocksInfoPayload
            }
            finalBytes = headBytes + blocksBytes
        }

        // 回填 size
        writeInt64At(finalBytes, sizeFieldPos, finalBytes.size.toLong(), EndianType.BigEndian)
        return finalBytes
    }

    private class Block(val payload: ByteArray, val uncompressedSize: Int, val flags: Int)

    // ============================ 写入工具 ============================

    private fun writeString(out: ByteArrayOutputStream, s: String) {
        try {
            out.write(s.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            out.write(s.toByteArray(Charsets.ISO_8859_1))
        }
        out.write(0)
    }

    private fun writeInt32(out: ByteArrayOutputStream, v: Int, endian: EndianType) {
        val b = ByteArray(4)
        if (endian == EndianType.BigEndian) {
            b[0] = (v ushr 24 and 0xFF).toByte(); b[1] = (v ushr 16 and 0xFF).toByte()
            b[2] = (v ushr 8 and 0xFF).toByte(); b[3] = (v and 0xFF).toByte()
        } else {
            b[0] = (v and 0xFF).toByte(); b[1] = (v ushr 8 and 0xFF).toByte()
            b[2] = (v ushr 16 and 0xFF).toByte(); b[3] = (v ushr 24 and 0xFF).toByte()
        }
        out.write(b)
    }

    private fun writeInt16(out: ByteArrayOutputStream, v: Int, endian: EndianType) {
        val b = ByteArray(2)
        if (endian == EndianType.BigEndian) {
            b[0] = (v ushr 8 and 0xFF).toByte(); b[1] = (v and 0xFF).toByte()
        } else {
            b[0] = (v and 0xFF).toByte(); b[1] = (v ushr 8 and 0xFF).toByte()
        }
        out.write(b)
    }

    private fun writeInt64(out: ByteArrayOutputStream, v: Long, endian: EndianType) {
        val b = ByteArray(8)
        if (endian == EndianType.BigEndian) {
            for (i in 0 until 8) b[i] = ((v ushr (8 * (7 - i))) and 0xFF).toByte()
        } else {
            for (i in 0 until 8) b[i] = ((v ushr (8 * i)) and 0xFF).toByte()
        }
        out.write(b)
    }

    private fun writeInt64At(buffer: ByteArray, offset: Int, v: Long, endian: EndianType) {
        if (endian == EndianType.BigEndian) {
            for (i in 0 until 8) buffer[offset + i] = ((v ushr (8 * (7 - i))) and 0xFF).toByte()
        } else {
            for (i in 0 until 8) buffer[offset + i] = ((v ushr (8 * i)) and 0xFF).toByte()
        }
    }

    /** 追加零填充直到 buffer 总长为 alignment 的倍数 */
    private fun alignBuffer(buffer: ByteArray, alignment: Int): ByteArray =
        alignBufferTo(buffer, buffer.size, alignment)

    /**
     * 在 buffer 之后补零，使"补零后的某个逻辑区段起点"对齐。
     * @param currentLen 已写入的内容长度
     * @param alignStart 希望对齐的位置（当前 buffer 末尾之后的新区段起点）
     */
    private fun alignBufferTo(buffer: ByteArray, currentLen: Int, alignment: Int): ByteArray {
        val rem = currentLen % alignment
        if (rem == 0) return buffer
        val pad = alignment - rem
        return buffer + ByteArray(pad)
    }
}

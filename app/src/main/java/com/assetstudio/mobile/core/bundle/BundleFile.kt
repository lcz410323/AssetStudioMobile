package com.assetstudio.mobile.core.bundle

import com.assetstudio.mobile.core.io.EndianBinaryReader
import com.assetstudio.mobile.core.io.EndianType
import com.assetstudio.mobile.core.io.getFileName
import java.io.IOException

/** 来自 BundleFile.cs 的 [Flags] enum ArchiveFlags */
object ArchiveFlags {
    const val CompressionTypeMask = 0x3f
    const val BlocksAndDirectoryInfoCombined = 0x40
    const val BlocksInfoAtTheEnd = 0x80
    const val OldWebPluginCompatibility = 0x100
    const val BlockInfoNeedPaddingAtStart = 0x200
}

/** 来自 BundleFile.cs 的 [Flags] enum StorageBlockFlags */
object StorageBlockFlags {
    const val CompressionTypeMask = 0x3f
    const val Streamed = 0x40
}

/** 来自 BundleFile.cs 的 enum CompressionType */
enum class CompressionType(val value: Int) {
    None(0),
    Lzma(1),
    Lz4(2),
    Lz4HC(3),
    Lzham(4);

    companion object {
        fun fromValue(value: Int): CompressionType? = entries.firstOrNull { it.value == value }
    }
}

/**
 * 来自 AssetStudio/BundleFile.cs，UnityFS / UnityWeb / UnityRaw(v6) 三种格式的完整解析。
 *
 * 与 C# 的差异（Android 内存模型）：
 * - C# 的 CreateBlocksStream 在 >2GB 时回退临时文件，这里统一用 ByteArray，
 *   超出 Int.MAX_VALUE 直接抛 IOException（可接受）。
 * - C# 的 blocksStream.CopyTo(file.stream, size)（StreamExtensions.cs，精确拷贝 size 字节）
 *   对应这里的 copyOfRange。
 */
class BundleFile(val data: ByteArray, val fileName: String) {

    class Header {
        var signature: String = ""
        var version: Long = 0 // uint
        var unityVersion: String = ""
        var unityRevision: String = ""
        var size: Long = 0
        var compressedBlocksInfoSize: Long = 0 // uint
        var uncompressedBlocksInfoSize: Long = 0 // uint
        var flags: Int = 0 // ArchiveFlags
    }

    class StorageBlock {
        var compressedSize: Long = 0 // uint
        var uncompressedSize: Long = 0 // uint
        var flags: Int = 0 // StorageBlockFlags
    }

    class Node {
        var offset: Long = 0
        var size: Long = 0
        var flags: Long = 0 // uint
        var path: String = ""
    }

    val m_Header = Header()

    var m_BlocksInfo: Array<StorageBlock> = emptyArray()
        private set

    var m_DirectoryInfo: Array<Node> = emptyArray()
        private set

    var fileList: List<StreamFile> = emptyList()
        private set

    init {
        val reader = EndianBinaryReader(data, EndianType.BigEndian)
        m_Header.signature = reader.readStringToNull()
        m_Header.version = reader.readUInt32()
        m_Header.unityVersion = reader.readStringToNull()
        m_Header.unityRevision = reader.readStringToNull()
        when (m_Header.signature) {
            "UnityArchive" -> {
                // TODO: C# 原版同样未实现
            }
            "UnityWeb", "UnityRaw" -> {
                if (m_Header.version == 6L) {
                    // C#: goto case "UnityFS"
                    readHeader(reader)
                    readBlocksInfoAndDirectory(reader)
                    val blocksStream = createBlocksStream()
                    readBlocks(reader, blocksStream)
                    readFiles(blocksStream)
                } else {
                    readHeaderAndBlocksInfo(reader)
                    val blocksStream = createBlocksStream()
                    readBlocksAndDirectory(reader, blocksStream)
                    readFiles(blocksStream)
                }
            }
            "UnityFS" -> {
                readHeader(reader)
                readBlocksInfoAndDirectory(reader)
                val blocksStream = createBlocksStream()
                readBlocks(reader, blocksStream)
                readFiles(blocksStream)
            }
        }
    }

    /** UnityWeb/UnityRaw 旧格式头部与块信息（对应 C# ReadHeaderAndBlocksInfo） */
    private fun readHeaderAndBlocksInfo(reader: EndianBinaryReader) {
        if (m_Header.version >= 4) {
            val hash = reader.readBytes(16)
            val crc = reader.readUInt32()
        }
        val minimumStreamedBytes = reader.readUInt32()
        m_Header.size = reader.readUInt32()
        val numberOfLevelsToDownloadBeforeStreaming = reader.readUInt32()
        val levelCount = reader.readInt32()
        m_BlocksInfo = arrayOf(StorageBlock())
        for (i in 0 until levelCount) {
            val storageBlock = StorageBlock()
            storageBlock.compressedSize = reader.readUInt32()
            storageBlock.uncompressedSize = reader.readUInt32()
            if (i == levelCount - 1) {
                m_BlocksInfo[0] = storageBlock
            }
        }
        if (m_Header.version >= 2) {
            val completeFileSize = reader.readUInt32()
        }
        if (m_Header.version >= 3) {
            val fileInfoHeaderSize = reader.readUInt32()
        }
        reader.position = m_Header.size.toInt()
    }

    /**
     * 对应 C# CreateBlocksStream：解压后块数据的总缓冲。
     * Android 端统一使用 ByteArray，>2GB 抛异常。
     */
    private fun createBlocksStream(): ByteArray {
        val uncompressedSizeSum = m_BlocksInfo.sumOf { it.uncompressedSize }
        if (uncompressedSizeSum >= Int.MAX_VALUE) {
            throw IOException("Bundle decompressed size $uncompressedSizeSum exceeds ByteArray limit (2GB)")
        }
        return ByteArray(uncompressedSizeSum.toInt())
    }

    /** UnityWeb/UnityRaw 旧格式块解压与目录（对应 C# ReadBlocksAndDirectory） */
    private fun readBlocksAndDirectory(reader: EndianBinaryReader, blocksStream: ByteArray) {
        val isCompressed = m_Header.signature == "UnityWeb"
        var streamPos = 0
        for (blockInfo in m_BlocksInfo) {
            var uncompressedBytes = readUpTo(reader, blockInfo.compressedSize.toInt())
            if (isCompressed) {
                uncompressedBytes = SevenZipHelper.streamDecompress(uncompressedBytes)
            }
            ensureBlocksCapacity(blocksStream, streamPos, uncompressedBytes.size)
            System.arraycopy(uncompressedBytes, 0, blocksStream, streamPos, uncompressedBytes.size)
            streamPos += uncompressedBytes.size
        }
        val blocksReader = EndianBinaryReader(blocksStream, EndianType.BigEndian)
        val nodesCount = blocksReader.readInt32()
        m_DirectoryInfo = Array(nodesCount) {
            Node().apply {
                path = blocksReader.readStringToNull()
                offset = blocksReader.readUInt32()
                size = blocksReader.readUInt32()
            }
        }
    }

    /** 对应 C# ReadFiles：按目录节点切分块数据为各文件 */
    private fun readFiles(blocksStream: ByteArray) {
        fileList = m_DirectoryInfo.map { node ->
            if (node.size >= Int.MAX_VALUE) {
                throw IOException("Bundle entry ${node.path} size ${node.size} exceeds ByteArray limit (2GB)")
            }
            val start = node.offset.toInt()
            val available = if (start < 0 || start >= blocksStream.size) 0 else blocksStream.size - start
            // 对应 C# CopyTo 的短读容错：不足时拷贝剩余全部
            val count = minOf(node.size.toInt(), available)
            val fileData = if (count > 0) blocksStream.copyOfRange(start, start + count) else ByteArray(0)
            StreamFile(node.path, getFileName(node.path), fileData)
        }
    }

    /** UnityFS 头部（对应 C# ReadHeader） */
    private fun readHeader(reader: EndianBinaryReader) {
        m_Header.size = reader.readInt64()
        m_Header.compressedBlocksInfoSize = reader.readUInt32()
        m_Header.uncompressedBlocksInfoSize = reader.readUInt32()
        m_Header.flags = reader.readUInt32().toInt()
        if (m_Header.signature != "UnityFS") {
            reader.readInt8()
        }
    }

    /** UnityFS 块信息与目录（对应 C# ReadBlocksInfoAndDirectory） */
    private fun readBlocksInfoAndDirectory(reader: EndianBinaryReader) {
        if (m_Header.version >= 7) {
            reader.alignStream(16)
        }
        val blocksInfoBytes: ByteArray
        if (m_Header.flags and ArchiveFlags.BlocksInfoAtTheEnd != 0) {
            val position = reader.position
            reader.position = reader.length - m_Header.compressedBlocksInfoSize.toInt()
            blocksInfoBytes = readUpTo(reader, m_Header.compressedBlocksInfoSize.toInt())
            reader.position = position
        } else { // 0x40 BlocksAndDirectoryInfoCombined
            blocksInfoBytes = readUpTo(reader, m_Header.compressedBlocksInfoSize.toInt())
        }
        val uncompressedInfoSize = m_Header.uncompressedBlocksInfoSize
        val compressionType = CompressionType.fromValue(m_Header.flags and ArchiveFlags.CompressionTypeMask)
        val blocksInfoUncompressed: ByteArray = when (compressionType) {
            CompressionType.None -> blocksInfoBytes
            CompressionType.Lzma -> SevenZipHelper.streamDecompress(
                blocksInfoBytes, 0, blocksInfoBytes.size, uncompressedInfoSize.toInt()
            )
            CompressionType.Lz4, CompressionType.Lz4HC -> {
                val uncompressedBytes = ByteArray(uncompressedInfoSize.toInt())
                val numWrite = LZ4.decode(
                    blocksInfoBytes, 0, blocksInfoBytes.size,
                    uncompressedBytes, 0, uncompressedBytes.size
                )
                if (numWrite != uncompressedBytes.size) {
                    throw IOException("Lz4 decompression error, write $numWrite bytes but expected ${uncompressedBytes.size} bytes")
                }
                uncompressedBytes
            }
            else -> throw IOException("Unsupported compression type $compressionType")
        }
        val blocksInfoReader = EndianBinaryReader(blocksInfoUncompressed, EndianType.BigEndian)
        val uncompressedDataHash = blocksInfoReader.readBytes(16)
        val blocksInfoCount = blocksInfoReader.readInt32()
        m_BlocksInfo = Array(blocksInfoCount) {
            StorageBlock().apply {
                uncompressedSize = blocksInfoReader.readUInt32()
                compressedSize = blocksInfoReader.readUInt32()
                flags = blocksInfoReader.readUInt16()
            }
        }

        val nodesCount = blocksInfoReader.readInt32()
        m_DirectoryInfo = Array(nodesCount) {
            Node().apply {
                offset = blocksInfoReader.readInt64()
                size = blocksInfoReader.readInt64()
                flags = blocksInfoReader.readUInt32()
                path = blocksInfoReader.readStringToNull()
            }
        }
        if (m_Header.flags and ArchiveFlags.BlockInfoNeedPaddingAtStart != 0) {
            reader.alignStream(16)
        }
    }

    /** UnityFS 数据块解压（对应 C# ReadBlocks） */
    private fun readBlocks(reader: EndianBinaryReader, blocksStream: ByteArray) {
        var streamPos = 0
        for (blockInfo in m_BlocksInfo) {
            val compressionType = CompressionType.fromValue(blockInfo.flags and StorageBlockFlags.CompressionTypeMask)
            when (compressionType) {
                CompressionType.None -> {
                    val bytes = readUpTo(reader, blockInfo.compressedSize.toInt())
                    ensureBlocksCapacity(blocksStream, streamPos, bytes.size)
                    System.arraycopy(bytes, 0, blocksStream, streamPos, bytes.size)
                    streamPos += bytes.size
                }
                CompressionType.Lzma -> {
                    // C#: StreamDecompress(reader.BaseStream, blocksStream, compressedSize, uncompressedSize)
                    val uncompressed = SevenZipHelper.streamDecompress(
                        reader.buffer, reader.position,
                        blockInfo.compressedSize.toInt(), blockInfo.uncompressedSize.toInt()
                    )
                    reader.position += blockInfo.compressedSize.toInt()
                    ensureBlocksCapacity(blocksStream, streamPos, uncompressed.size)
                    System.arraycopy(uncompressed, 0, blocksStream, streamPos, uncompressed.size)
                    streamPos += uncompressed.size
                }
                CompressionType.Lz4, CompressionType.Lz4HC -> {
                    val compressedSize = blockInfo.compressedSize.toInt()
                    val compressedBytes = readUpTo(reader, compressedSize)
                    val uncompressedSize = blockInfo.uncompressedSize.toInt()
                    val uncompressedBytes = ByteArray(uncompressedSize)
                    val numWrite = LZ4.decode(
                        compressedBytes, 0, compressedBytes.size,
                        uncompressedBytes, 0, uncompressedSize
                    )
                    if (numWrite != uncompressedSize) {
                        throw IOException("Lz4 decompression error, write $numWrite bytes but expected $uncompressedSize bytes")
                    }
                    ensureBlocksCapacity(blocksStream, streamPos, uncompressedSize)
                    System.arraycopy(uncompressedBytes, 0, blocksStream, streamPos, uncompressedSize)
                    streamPos += uncompressedSize
                }
                else -> throw IOException("Unsupported compression type $compressionType")
            }
        }
    }

    /** 最多读取 count 字节（对应 C# Stream.Read 的短读语义） */
    private fun readUpTo(reader: EndianBinaryReader, count: Int): ByteArray {
        val actual = minOf(count, reader.remaining)
        return if (actual > 0) reader.readBytes(actual) else ByteArray(0)
    }

    private fun ensureBlocksCapacity(blocksStream: ByteArray, pos: Int, size: Int) {
        if (pos + size > blocksStream.size) {
            throw IOException("Bundle block data overflow: pos=$pos, size=$size, total=${blocksStream.size}")
        }
    }
}

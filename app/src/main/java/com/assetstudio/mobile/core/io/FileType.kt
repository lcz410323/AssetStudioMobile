package com.assetstudio.mobile.core.io

/**
 * 文件类型枚举，来自 AssetStudio/FileType.cs。
 * 检测逻辑来自 AssetStudio/FileReader.cs 的 CheckFileType / IsSerializedFile。
 */
enum class FileType {
    AssetsFile,
    BundleFile,
    WebFile,
    ResourceFile,
    TextFile,
    GZipFile,
    BrotliFile,
    ZipFile
}

/**
 * 文件类型嗅探器，忠实移植 FileReader.cs 的 CheckFileType() 逻辑：
 * 1. 头部以 \0 结尾的签名字符串（最多 20 字节）：UnityFS/UnityWeb/UnityRaw/UnityArchive => BundleFile，
 *    UnityWebData1.0 => WebFile
 * 2. gzip 魔数 1F 8B => GZipFile
 * 3. 偏移 0x20 处 "brotli" => BrotliFile
 * 4. 满足 Unity SerializedFile 头部约束（fileSize 匹配、dataOffset 合法）=> AssetsFile
 * 5. zip 魔数 PK\x03\x04 / PK\x07\x08 => ZipFile
 * 6. 其余 => ResourceFile
 */
object FileTypeDetector {

    private val gzipMagic = byteArrayOf(0x1f.toByte(), 0x8b.toByte())
    private val brotliMagic = byteArrayOf(0x62, 0x72, 0x6F, 0x74, 0x6C, 0x69) // "brotli"
    private val zipMagic = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val zipSpannedMagic = byteArrayOf(0x50, 0x4B, 0x07, 0x08)

    fun detect(fileName: String, bytes: ByteArray, offset: Int = 0): FileType {
        // ReadStringToNull(20)：读取最多 20 字节的签名字符串（遇到 \0 停止）
        val signature = run {
            val reader = EndianBinaryReader(bytes, EndianType.BigEndian, offset)
            reader.readStringToNull(20)
        }
        return when (signature) {
            "UnityWeb", "UnityRaw", "UnityArchive", "UnityFS" -> FileType.BundleFile
            "UnityWebData1.0" -> FileType.WebFile
            else -> {
                // gzip 魔数（头部 2 字节）
                if (matchMagic(bytes, offset, gzipMagic)) {
                    return FileType.GZipFile
                }
                // brotli 特征：偏移 0x20 处的 "brotli" 字符串
                if (matchMagic(bytes, offset + 0x20, brotliMagic)) {
                    return FileType.BrotliFile
                }
                if (isSerializedFile(bytes, offset)) {
                    return FileType.AssetsFile
                }
                if (matchMagic(bytes, offset, zipMagic) || matchMagic(bytes, offset, zipSpannedMagic)) {
                    return FileType.ZipFile
                }
                FileType.ResourceFile
            }
        }
    }

    private fun matchMagic(bytes: ByteArray, offset: Int, magic: ByteArray): Boolean {
        if (offset < 0 || offset + magic.size > bytes.size) return false
        for (i in magic.indices) {
            if (bytes[offset + i] != magic[i]) return false
        }
        return true
    }

    /**
     * 来自 FileReader.cs 的 IsSerializedFile()：
     * 用大端读取 SerializedFile 头部并校验 fileSize 与 dataOffset。
     */
    private fun isSerializedFile(bytes: ByteArray, offset: Int): Boolean {
        val fileSize = bytes.size - offset
        if (fileSize < 20) {
            return false
        }
        val reader = EndianBinaryReader(bytes, EndianType.BigEndian, offset)
        var mMetadataSize = reader.readUInt32()
        var mFileSize = reader.readUInt32()
        val mVersion = reader.readUInt32()
        var mDataOffset = reader.readUInt32()
        val mEndianess = reader.readUInt8()
        val mReserved = reader.readBytes(3)
        if (mVersion >= 22) {
            if (fileSize < 48) {
                return false
            }
            mMetadataSize = reader.readUInt32()
            mFileSize = reader.readInt64()
            mDataOffset = reader.readInt64()
        }
        if (mFileSize != fileSize.toLong()) {
            return false
        }
        if (mDataOffset > fileSize) {
            return false
        }
        return true
    }
}

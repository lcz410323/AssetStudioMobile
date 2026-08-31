package com.assetstudio.mobile.core.io

/**
 * 来自 AssetStudio/FileReader.cs 的内存版适配：
 * C# 版基于 Stream（FullPath/FileName/FileType + 继承 EndianBinaryReader），
 * Android 端统一改为持有原始 ByteArray，检测逻辑委托给 FileTypeDetector。
 *
 * 附带路径工具函数（等价 C# 的 Path.GetFileName / Path.GetDirectoryName，
 * 同时接受 '/' 与 '\' 分隔符）。
 */
class FileReader(val fullPath: String, val data: ByteArray) {

    /** 文件名（不含目录），等价 C# 的 FileName */
    val fileName: String = getFileName(fullPath)

    /** 嗅探出的文件类型，等价 C# 的 FileType */
    val fileType: FileType = FileTypeDetector.detect(fileName, data)

    /** 大端读取器（等价 C# FileReader 的 BaseStream 视角） */
    val reader: EndianBinaryReader = EndianBinaryReader(data, EndianType.BigEndian)
}

/** 等价 Path.GetFileName：截取最后一个 '/' 或 '\\' 之后的部分 */
fun getFileName(path: String): String {
    val normalized = path.trimEnd('/', '\\')
    val idx = normalized.lastIndexOfAny(charArrayOf('/', '\\'))
    return if (idx >= 0) normalized.substring(idx + 1) else normalized
}

/** 等价 Path.GetDirectoryName：截取最后一个分隔符之前的部分（无分隔符返回空串） */
fun getDirectoryName(path: String): String {
    val idx = path.lastIndexOfAny(charArrayOf('/', '\\'))
    return if (idx > 0) path.substring(0, idx) else if (idx == 0) "/" else ""
}

/** 等价 Path.Combine（使用 '/' 连接，忽略空段） */
fun combinePath(dir: String, name: String): String {
    if (dir.isEmpty()) return name
    if (dir.endsWith("/") || dir.endsWith("\\")) return dir + name
    return "$dir/$name"
}

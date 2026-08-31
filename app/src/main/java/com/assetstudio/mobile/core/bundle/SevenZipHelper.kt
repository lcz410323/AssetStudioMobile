package com.assetstudio.mobile.core.bundle

import com.assetstudio.mobile.core.bundle.lzma.LzmaDecoder
import java.io.IOException

/**
 * 来自 AssetStudio/SevenZipHelper.cs。
 * LZMA 解码器为本项目移植的 core/bundle/lzma/LzmaDecoder.kt（7zip SDK）。
 */
object SevenZipHelper {

    /**
     * 对应 C# StreamDecompress(MemoryStream)：
     * 输入格式 = 5 字节 props + 8 字节小端解压后大小 + LZMA 数据流。
     */
    fun streamDecompress(data: ByteArray): ByteArray {
        if (data.size < 13) {
            throw IOException("input .lzma is too short")
        }
        val properties = data.copyOfRange(0, 5)
        var outSize = 0L
        for (i in 0 until 8) {
            outSize = outSize or ((data[5 + i].toLong() and 0xFF) shl (8 * i))
        }
        if (outSize > Int.MAX_VALUE) {
            throw IOException("Decompressed size too large for ByteArray: $outSize")
        }
        val decoder = LzmaDecoder()
        decoder.setDecoderProperties(properties)
        val out = ByteArray(outSize.toInt())
        decoder.code(data, 13, data.size - 13, out, 0, outSize.toInt())
        return out
    }

    /**
     * 对应 C# StreamDecompress(Stream, Stream, compressedSize, decompressedSize)：
     * bundle 块格式，5 字节 props 开头（计入 compressedSize），
     * 之后为 compressedSize - 5 字节的 LZMA 数据，解压出 decompressedSize 字节。
     */
    fun streamDecompress(src: ByteArray, srcPos: Int, compressedSize: Int, decompressedSize: Int): ByteArray {
        if (compressedSize < 5) {
            throw IOException("input .lzma is too short")
        }
        if (srcPos + compressedSize > src.size) {
            throw IOException("lzma block out of range: srcPos=$srcPos, compressedSize=$compressedSize, size=${src.size}")
        }
        val properties = src.copyOfRange(srcPos, srcPos + 5)
        val decoder = LzmaDecoder()
        decoder.setDecoderProperties(properties)
        val out = ByteArray(decompressedSize)
        decoder.code(src, srcPos + 5, compressedSize - 5, out, 0, decompressedSize)
        return out
    }
}

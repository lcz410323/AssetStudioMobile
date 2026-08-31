package com.assetstudio.mobile.core.io

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/**
 * GZip 解压，来自 AssetStudio/ImportHelper.cs 的 DecompressGZip()。
 * 使用纯 JVM 的 java.util.zip.GZIPInputStream。
 */
object Gzip {

    fun decompress(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(maxOf(data.size * 2, 64))
        GZIPInputStream(ByteArrayInputStream(data)).use { gzip ->
            gzip.copyTo(output)
        }
        return output.toByteArray()
    }
}

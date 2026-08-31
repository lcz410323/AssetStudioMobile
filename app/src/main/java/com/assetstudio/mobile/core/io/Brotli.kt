package com.assetstudio.mobile.core.io

import org.brotli.dec.BrotliInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Brotli 解压，来自 AssetStudio/ImportHelper.cs 的 DecompressBrotli()。
 * C# 使用 Org.Brotli.Dec.BrotliInputStream，Kotlin 对应 org.brotli:dec 的 BrotliInputStream。
 */
object Brotli {

    fun decompress(data: ByteArray): ByteArray {
        ByteArrayInputStream(data).use { input ->
            BrotliInputStream(input).use { brotli ->
                val output = ByteArrayOutputStream(data.size)
                brotli.copyTo(output)
                return output.toByteArray()
            }
        }
    }
}

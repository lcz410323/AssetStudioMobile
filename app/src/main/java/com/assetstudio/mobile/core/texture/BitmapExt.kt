package com.assetstudio.mobile.core.texture

/*
 * 来源: AssetStudio/AssetStudioUtility/Texture2DExtensions.cs
 *
 * C# ConvertToImage(flip) / ConvertToStream(...) 的 Android 对应实现：
 * TextureConverter 解码出的 0xAARRGGBB IntArray -> android.graphics.Bitmap(ARGB_8888)。
 *
 * 说明:
 * - 本文件是 core 模块中【唯一】允许 import android.* 的文件（胶水层）。
 * - Unity 纹理数据以 OpenGL 习惯存储（row 0 = 底部），与 C# 侧 Flip(FlipMode.Vertical)
 *   一致，默认对像素做垂直翻转（flip = true）。
 * - 超大图（> 4096*4096 像素）先做降采样，避免 Bitmap 分配直接 OOM；
 *   创建 Bitmap 时若仍发生 OOM 则捕获并返回 null（对应 C# 侧返回 null 的语义）。
 */

import android.content.Context
import android.graphics.Bitmap
import com.assetstudio.mobile.core.classes.Texture2D
import kotlin.math.max
import kotlin.math.sqrt

/** decodeBitmap 的失败信息记录，便于上层提示 */
object TextureBitmapExt {
    @Volatile
    var lastError: String? = null
        internal set

    internal fun fail(message: String) {
        lastError = message
    }
}

/** 默认降采样阈值：超过该像素总数的纹理先缩小再生成 Bitmap */
private const val MAX_TEXTURE_PIXELS = 4096 * 4096

/**
 * 将 Texture2D 解码为 Android Bitmap。
 *
 * @param context  预留的上下文参数（当前解码流程不需要，仅保持调用方签名稳定）
 * @param flip     是否垂直翻转（默认 true，与 AssetStudio 导出行为一致）
 * @param maxPixels 超过该像素数先降采样，默认 4096*4096
 * @return 解码成功返回 Bitmap；失败（数据缺失/格式不支持/OOM）返回 null，
 *         失败原因记录在 [TextureBitmapExt.lastError]。
 */
fun Texture2D.decodeBitmap(
    context: Context? = null,
    flip: Boolean = true,
    maxPixels: Int = MAX_TEXTURE_PIXELS
): Bitmap? {
    TextureBitmapExt.lastError = null
    val converter = TextureConverter(this)
    var width = m_Width
    var height = m_Height
    var pixels: IntArray = converter.decodeToPixels() ?: run {
        TextureBitmapExt.fail(converter.lastError ?: "Decode texture failed")
        return null
    }

    // 超大图先降采样，避免直接分配超大 Bitmap 导致 OOM
    if (maxPixels > 0 && width * height > maxPixels) {
        try {
            val scale = sqrt(maxPixels.toDouble() / (width.toDouble() * height))
            val newWidth = max(1, (width * scale).toInt())
            val newHeight = max(1, (height * scale).toInt())
            pixels = downsample(pixels, width, height, newWidth, newHeight)
            width = newWidth
            height = newHeight
        } catch (e: OutOfMemoryError) {
            TextureBitmapExt.fail("Downsample ${m_Width}x${m_Height} failed: OOM")
            return null
        }
    }

    if (flip) {
        PixelFlip.flipVerticalInPlace(pixels, width, height)
    }

    return try {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        bitmap
    } catch (e: OutOfMemoryError) {
        TextureBitmapExt.fail("Create bitmap ${width}x${height} failed: OOM")
        null
    } catch (e: Throwable) {
        TextureBitmapExt.fail("Create bitmap ${width}x${height} failed: ${e.message}")
        null
    }
}

/** 仅解码像素（不做 Bitmap 转换），供 Compose/OpenGL 等其它消费方使用 */
fun Texture2D.decodePixels(flip: Boolean = true): IntArray? {
    val converter = TextureConverter(this)
    val pixels = converter.decodeToPixels() ?: return null
    if (flip) {
        PixelFlip.flipVerticalInPlace(pixels, m_Width, m_Height)
    }
    return pixels
}

/** 最近邻降采样（中心取样），足够预览用途且不引入额外依赖 */
private fun downsample(src: IntArray, srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int): IntArray {
    val dst = IntArray(dstWidth * dstHeight)
    val xRatio = srcWidth.toDouble() / dstWidth
    val yRatio = srcHeight.toDouble() / dstHeight
    var index = 0
    for (y in 0 until dstHeight) {
        val sy = (((y + 0.5) * yRatio).toInt()).coerceIn(0, srcHeight - 1)
        val row = sy * srcWidth
        for (x in 0 until dstWidth) {
            val sx = (((x + 0.5) * xRatio).toInt()).coerceIn(0, srcWidth - 1)
            dst[index++] = src[row + sx]
        }
    }
    return dst
}

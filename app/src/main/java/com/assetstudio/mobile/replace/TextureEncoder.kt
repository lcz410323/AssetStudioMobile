package com.assetstudio.mobile.replace

import com.assetstudio.mobile.core.classes.TextureFormat

/*
 * 贴图重编码器：把 0xAARRGGBB 像素重新编码回 Unity TextureFormat 数据。
 *
 * 设计（与 UABE 的"替换贴图"策略一致）：
 * - 可无损编码的未压缩格式（RGBA32/ARGB32/RGB24/BGRA32/Alpha8/R8/RGB565/ARGB4444）
 *   保持原格式回写；
 * - DXT1/DXT5 提供快速块压缩编码器（质量略低于原始压缩器，但体积小、兼容性好）；
 * - ASTC 4x4/5x5/6x6/8x8（RGB/RGBA）提供软件编码器（AstcCodec，Khronos 规范实现，
 *   已用独立解码器 texture2ddecoder 交叉验证），手机游戏贴图最常见格式按原格式回写；
 * - 其余格式（BC6/BC7/ASTC HDR/10x10/12x12/ETC/PVRTC/Crunch 等）统一回退为 RGBA32，
 *   并同步修改对象内的 m_TextureFormat 字段 —— Unity 运行时按序列化字段加载，
 *   RGBA32 全平台支持，因此任何游戏都可正常读取。
 */

/** 一级 mip 数据：像素 + 尺寸 */
class MipLevel(val width: Int, val height: Int, val pixels: IntArray) {
    val size: Int get() = width * height
}

object TextureEncoder {

    /** 该格式是否可以被本编码器按原格式回写 */
    fun isFormatEncodable(format: TextureFormat): Boolean = format in encodableFormats

    private val encodableFormats = setOf(
        TextureFormat.Alpha8,
        TextureFormat.ARGB4444,
        TextureFormat.RGB24,
        TextureFormat.RGBA32,
        TextureFormat.ARGB32,
        TextureFormat.RGB565,
        TextureFormat.BGRA32,
        TextureFormat.R8,
        TextureFormat.DXT1,
        TextureFormat.DXT5,
        // ASTC：RGB 与 RGBA 的位流布局相同（解码端只是是否暴露 alpha 的差别），
        // 统一用 AstcCodec（CEM12 LDR RGBA）编码
        TextureFormat.ASTC_RGB_4x4,
        TextureFormat.ASTC_RGBA_4x4,
        TextureFormat.ASTC_RGB_5x5,
        TextureFormat.ASTC_RGBA_5x5,
        TextureFormat.ASTC_RGB_6x6,
        TextureFormat.ASTC_RGBA_6x6,
        TextureFormat.ASTC_RGB_8x8,
        TextureFormat.ASTC_RGBA_8x8,
    )

    /** 编码失败原因（最近一次 encode 调用） */
    var lastError: String? = null
        private set

    /**
     * 从首级像素生成完整 mip 链并编码为 [format]。
     *
     * @param mipCount 目标 mip 级数（含首级）；<=1 时只写首级
     * @return 全部 mip 串联后的数据；格式不可编码时返回 null
     */
    fun encode(
        pixels: IntArray,
        width: Int,
        height: Int,
        format: TextureFormat,
        mipCount: Int
    ): ByteArray? {
        lastError = null
        if (width <= 0 || height <= 0 || pixels.size < width * height) {
            lastError = "Invalid pixel buffer: ${width}x${height}, ${pixels.size} pixels"
            return null
        }
        if (!isFormatEncodable(format)) {
            lastError = "Format $format is not encodable"
            return null
        }

        // 生成 mip 链（box filter 下采样，与 Unity 的线性 mip 生成近似）
        val levels = ArrayList<MipLevel>()
        levels.add(MipLevel(width, height, pixels.copyOf(width * height)))
        val clampedMips = mipCount.coerceIn(1, 32)
        var w = width
        var h = height
        while (levels.size < clampedMips && (w > 1 || h > 1)) {
            w = (w ushr 1).coerceAtLeast(1)
            h = (h ushr 1).coerceAtLeast(1)
            levels.add(downsample(levels[levels.size - 1], w, h))
        }

        // 逐级编码
        val chunks = ArrayList<ByteArray>(levels.size)
        for (level in levels) {
            val encoded = encodeLevel(level, format)
                ?: run {
                    lastError = lastError ?: "Encode $format failed at mip ${levels.indexOf(level)}"
                    return null
                }
            chunks.add(encoded)
        }
        return concat(chunks)
    }

    // ============================ 下采样 ============================

    /** 2x2 box filter 下采样（边界为 1 时直接复制行/列） */
    private fun downsample(src: MipLevel, w: Int, h: Int): MipLevel {
        val dst = IntArray(w * h)
        for (y in 0 until h) {
            val sy0 = (y * 2).coerceAtMost(src.height - 1)
            val sy1 = ((y * 2 + 1)).coerceAtMost(src.height - 1)
            for (x in 0 until w) {
                val sx0 = (x * 2).coerceAtMost(src.width - 1)
                val sx1 = (x * 2 + 1).coerceAtMost(src.width - 1)
                val p00 = src.pixels[sy0 * src.width + sx0]
                val p01 = src.pixels[sy0 * src.width + sx1]
                val p10 = src.pixels[sy1 * src.width + sx0]
                val p11 = src.pixels[sy1 * src.width + sx1]
                dst[y * w + x] = average4(p00, p01, p10, p11)
            }
        }
        return MipLevel(w, h, dst)
    }

    private fun average4(a: Int, b: Int, c: Int, d: Int): Int {
        val ar = a ushr 16 and 0xFF; val ag = a ushr 8 and 0xFF; val ab = a and 0xFF; val aa = a ushr 24 and 0xFF
        val br = b ushr 16 and 0xFF; val bg = b ushr 8 and 0xFF; val bb = b and 0xFF; val ba = b ushr 24 and 0xFF
        val cr = c ushr 16 and 0xFF; val cg = c ushr 8 and 0xFF; val cb = c and 0xFF; val ca = c ushr 24 and 0xFF
        val dr = d ushr 16 and 0xFF; val dg = d ushr 8 and 0xFF; val db = d and 0xFF; val da = d ushr 24 and 0xFF
        val r = (ar + br + cr + dr + 2) shr 2
        val g = (ag + bg + cg + dg + 2) shr 2
        val bl = (ab + bb + cb + db + 2) shr 2
        val al = (aa + ba + ca + da + 2) shr 2
        return (al shl 24) or (r shl 16) or (g shl 8) or bl
    }

    // ============================ 单级编码 ============================

    private fun encodeLevel(level: MipLevel, format: TextureFormat): ByteArray? {
        return when (format) {
            TextureFormat.RGBA32 -> encodeBytes(level) { p -> byteArrayOf(r(p), g(p), b(p), a(p)) }
            TextureFormat.ARGB32 -> encodeBytes(level) { p -> byteArrayOf(a(p), r(p), g(p), b(p)) }
            TextureFormat.BGRA32 -> encodeBytes(level) { p -> byteArrayOf(b(p), g(p), r(p), a(p)) }
            TextureFormat.RGB24 -> encodeBytes(level) { p -> byteArrayOf(r(p), g(p), b(p)) }
            TextureFormat.Alpha8 -> encodeBytes(level) { p -> byteArrayOf(a(p)) }
            TextureFormat.R8 -> encodeBytes(level) { p -> byteArrayOf(r(p)) }
            TextureFormat.RGB565 -> encodeRgb565(level)
            TextureFormat.ARGB4444 -> encodeArgb4444(level)
            TextureFormat.DXT1 -> encodeDxt1(level)
            TextureFormat.DXT5 -> encodeDxt5(level)
            // ASTC 任意尺寸均可编码（块边缘填充），无需尺寸对齐检查
            TextureFormat.ASTC_RGB_4x4, TextureFormat.ASTC_RGBA_4x4 ->
                AstcCodec.encode(level.pixels, level.width, level.height, 4)
            TextureFormat.ASTC_RGB_5x5, TextureFormat.ASTC_RGBA_5x5 ->
                AstcCodec.encode(level.pixels, level.width, level.height, 5)
            TextureFormat.ASTC_RGB_6x6, TextureFormat.ASTC_RGBA_6x6 ->
                AstcCodec.encode(level.pixels, level.width, level.height, 6)
            TextureFormat.ASTC_RGB_8x8, TextureFormat.ASTC_RGBA_8x8 ->
                AstcCodec.encode(level.pixels, level.width, level.height, 8)
            else -> null
        }
    }

    private fun r(p: Int) = (p ushr 16 and 0xFF).toByte()
    private fun g(p: Int) = (p ushr 8 and 0xFF).toByte()
    private fun b(p: Int) = (p and 0xFF).toByte()
    private fun a(p: Int) = (p ushr 24 and 0xFF).toByte()

    private fun encodeBytes(level: MipLevel, pixelToBytes: (Int) -> ByteArray): ByteArray {
        var size = 0
        for (i in 0 until level.size) size += pixelToBytes(level.pixels[i]).size
        val out = ByteArray(size)
        var o = 0
        for (i in 0 until level.size) {
            val px = pixelToBytes(level.pixels[i])
            for (x in px) out[o++] = x
        }
        return out
    }

    /** RGB565：小端 16 位（与解码端 readU16LE 对应） */
    private fun encodeRgb565(level: MipLevel): ByteArray {
        val out = ByteArray(level.size * 2)
        for (i in 0 until level.size) {
            val p = level.pixels[i]
            val v = ((r(p).toInt() and 0xFF) shr 3 shl 11) or
                ((g(p).toInt() and 0xFF) shr 2 shl 5) or
                (b(p).toInt() and 0xFF) shr 3
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = (v shr 8 and 0xFF).toByte()
        }
        return out
    }

    /** ARGB4444：小端 16 位 */
    private fun encodeArgb4444(level: MipLevel): ByteArray {
        val out = ByteArray(level.size * 2)
        for (i in 0 until level.size) {
            val p = level.pixels[i]
            val v = ((a(p).toInt() and 0xFF) shr 4 shl 12) or
                ((r(p).toInt() and 0xFF) shr 4 shl 8) or
                ((g(p).toInt() and 0xFF) shr 4 shl 4) or
                (b(p).toInt() and 0xFF) shr 4
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = (v shr 8 and 0xFF).toByte()
        }
        return out
    }

    // ============================ DXT 块压缩 ============================

    /**
     * DXT1(BC1) 编码：每 4x4 块 8 字节（2 个 565 端点 + 4 字节 2bit 索引）。
     * 端点用亮度极值法选取，4 色模式（c0 > c1）。
     */
    private fun encodeDxt1(level: MipLevel): ByteArray {
        val blocksW = (level.width + 3) shr 2
        val blocksH = (level.height + 3) shr 2
        val out = ByteArray(blocksW * blocksH * 8)
        var o = 0
        for (by in 0 until blocksH) {
            for (bx in 0 until blocksW) {
                val block = readBlock(level, bx, by)
                encodeBc1ColorBlock(block, out, o, punchthrough = false)
                o += 8
            }
        }
        return out
    }

    /** DXT5(BC3) 编码：alpha 块 + 颜色块 */
    private fun encodeDxt5(level: MipLevel): ByteArray {
        val blocksW = (level.width + 3) shr 2
        val blocksH = (level.height + 3) shr 2
        val out = ByteArray(blocksW * blocksH * 16)
        var o = 0
        for (by in 0 until blocksH) {
            for (bx in 0 until blocksW) {
                val block = readBlock(level, bx, by)
                encodeBc4AlphaBlock(block, out, o)
                o += 8
                encodeBc1ColorBlock(block, out, o, punchthrough = false)
                o += 8
            }
        }
        return out
    }

    /** 读取 4x4 块（不足处边缘填充） */
    private fun readBlock(level: MipLevel, bx: Int, by: Int): IntArray {
        val block = IntArray(16)
        for (y in 0 until 4) {
            val sy = (by * 4 + y).coerceAtMost(level.height - 1)
            for (x in 0 until 4) {
                val sx = (bx * 4 + x).coerceAtMost(level.width - 1)
                block[y * 4 + x] = level.pixels[sy * level.width + sx]
            }
        }
        return block
    }

    /**
     * BC1 颜色块编码（也可用于 BC2/BC3 的颜色部分）。
     * 端点：亮度最小/最大的两个像素；索引：与 4 色调色板最近邻。
     */
    private fun encodeBc1ColorBlock(block: IntArray, out: ByteArray, offset: Int, punchthrough: Boolean) {
        var minLum = Int.MAX_VALUE
        var maxLum = Int.MIN_VALUE
        var minPix = block[0]
        var maxPix = block[0]
        for (p in block) {
            val lum = luminance(p)
            if (lum < minLum) { minLum = lum; minPix = p }
            if (lum > maxLum) { maxLum = lum; maxPix = p }
        }

        val c0 = rgb888To565(maxPix)
        var c1 = rgb888To565(minPix)
        // 4 色模式要求 c0 > c1；相等时强制 c1-1（保持 4 色语义）
        if (c0 <= c1) {
            if (c1 > 0) c1 = c1 - 1 else if (c0 < 0xFFFF) {
                // c0 == c1 == 0：把 c0 提到 1
                writeU16LE(out, offset, 1)
                writeU16LE(out, offset + 2, 0)
                encodeIndicesForEndpoints(block, out, offset + 4, 1, 0)
                return
            }
        }
        writeU16LE(out, offset, c0)
        writeU16LE(out, offset + 2, c1)
        encodeIndicesForEndpoints(block, out, offset + 4, c0, c1)
    }

    /** 由 565 端点构造 4 色调色板并为每个像素写 2bit 索引 */
    private fun encodeIndicesForEndpoints(block: IntArray, out: ByteArray, offset: Int, c0: Int, c1: Int) {
        val pal = IntArray(4)
        pal[0] = expand565(c0)
        pal[1] = expand565(c1)
        pal[2] = interpolate233(pal[0], pal[1])
        pal[3] = interpolate323(pal[0], pal[1])

        var indices = 0
        for (i in 0 until 16) {
            val idx = nearestPaletteIndex(block[i], pal)
            indices = indices or (idx shl (i * 2))
        }
        // 4 字节小端索引
        out[offset] = (indices and 0xFF).toByte()
        out[offset + 1] = (indices shr 8 and 0xFF).toByte()
        out[offset + 2] = (indices shr 16 and 0xFF).toByte()
        out[offset + 3] = (indices shr 24 and 0xFF).toByte()
    }

    /** BC4/BC3 alpha 块：a0/a1 端点 + 16 个 3bit 索引（共 8 字节） */
    private fun encodeBc4AlphaBlock(block: IntArray, out: ByteArray, offset: Int) {
        var minA = 255
        var maxA = 0
        for (p in block) {
            val alpha = p ushr 24 and 0xFF
            if (alpha < minA) minA = alpha
            if (alpha > maxA) maxA = alpha
        }
        out[offset] = maxA.toByte()
        out[offset + 1] = minA.toByte()

        // 8 级调色板（max > min → 标准 8 值模式）
        val pal = IntArray(8)
        if (maxA > minA) {
            for (i in 0 until 8) {
                pal[i] = (maxA * (8 - 1 - i) + minA * i) / (8 - 1)
            }
        } else {
            for (i in 0 until 5) pal[i] = maxA
            pal[5] = 0; pal[6] = 0; pal[7] = 255
        }

        // 16 个 3bit 索引 → 48bit 小端打包
        var bitPos = 0
        var byteIdx = offset + 2
        var acc = 0
        for (i in 0 until 16) {
            val alpha = block[i] ushr 24 and 0xFF
            var best = 0
            var bestDist = Int.MAX_VALUE
            for (k in 0 until 8) {
                val d = kotlin.math.abs(pal[k] - alpha)
                if (d < bestDist) { bestDist = d; best = k }
            }
            acc = acc or (best shl bitPos)
            bitPos += 3
            if (bitPos >= 8) {
                out[byteIdx++] = (acc and 0xFF).toByte()
                acc = acc ushr 8
                bitPos -= 8
            }
        }
        while (byteIdx < offset + 8) {
            out[byteIdx++] = (acc and 0xFF).toByte()
            acc = acc ushr 8
        }
    }

    private fun luminance(p: Int): Int {
        val r = p ushr 16 and 0xFF
        val g = p ushr 8 and 0xFF
        val b = p and 0xFF
        return (r * 30 + g * 59 + b * 11) shr 7
    }

    private fun rgb888To565(p: Int): Int =
        ((p ushr 16 and 0xFF) shr 3 shl 11) or ((p ushr 8 and 0xFF) shr 2 shl 5) or (p and 0xFF shr 3)

    private fun expand565(v: Int): Int {
        val r = (v shr 11 and 0x1F) shl 3
        val g = (v shr 5 and 0x3F) shl 2
        val b = (v and 0x1F) shl 3
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** (2*c0 + c1)/3 */
    private fun interpolate233(c0: Int, c1: Int): Int {
        val r = ((c0 ushr 16 and 0xFF) * 2 + (c1 ushr 16 and 0xFF)) / 3
        val g = ((c0 shr 8 and 0xFF) * 2 + (c1 shr 8 and 0xFF)) / 3
        val b = ((c0 and 0xFF) * 2 + (c1 and 0xFF)) / 3
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** (c0 + 2*c1)/3 */
    private fun interpolate323(c0: Int, c1: Int): Int {
        val r = ((c0 ushr 16 and 0xFF) + (c1 ushr 16 and 0xFF) * 2) / 3
        val g = ((c0 shr 8 and 0xFF) + (c1 shr 8 and 0xFF) * 2) / 3
        val b = ((c0 and 0xFF) + (c1 and 0xFF) * 2) / 3
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun nearestPaletteIndex(pixel: Int, pal: IntArray): Int {
        val pr = pixel ushr 16 and 0xFF
        val pg = pixel ushr 8 and 0xFF
        val pb = pixel and 0xFF
        var best = 0
        var bestDist = Int.MAX_VALUE
        for (k in pal.indices) {
            val dr = pr - (pal[k] ushr 16 and 0xFF)
            val dg = pg - (pal[k] ushr 8 and 0xFF)
            val db = pb - (pal[k] and 0xFF)
            val d = dr * dr + dg * dg + db * db
            if (d < bestDist) { bestDist = d; best = k }
        }
        return best
    }

    private fun writeU16LE(out: ByteArray, offset: Int, v: Int) {
        out[offset] = (v and 0xFF).toByte()
        out[offset + 1] = (v shr 8 and 0xFF).toByte()
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

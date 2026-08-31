package com.assetstudio.mobile

import com.assetstudio.mobile.core.bundle.LZ4
import com.assetstudio.mobile.core.classes.TextureFormat
import com.assetstudio.mobile.replace.TextureEncoder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/*
 * 纯 JVM 单元测试：验证替换链路核心算法。
 * 运行: ./gradlew testDebugUnitTest
 */
class CoreAlgorithmTest {

    // ---------- LZ4 回环（Bundle 重打包的压缩基础） ----------

    @Test
    fun lz4RoundTripHighlyCompressible() {
        val src = ByteArray(200_000) { (it / 257).toByte() } // 大量重复 → 压缩比高
        val encoded = LZ4.encode(src)
        assertTrue(encoded.size < src.size / 4, "高重复数据应大幅压缩: ${encoded.size} vs ${src.size}")
        val decoded = ByteArray(src.size)
        val n = LZ4.decode(encoded, 0, encoded.size, decoded, 0, decoded.size)
        assertEquals(src.size, n, "解压长度应等于原始长度")
        assertContentEquals(src, decoded, "回环数据应一致")
    }

    @Test
    fun lz4RoundTripRandom() {
        val rnd = Random(42)
        val src = ByteArray(300_000) { rnd.nextInt().toByte() } // 不可压缩
        val encoded = LZ4.encode(src)
        val decoded = ByteArray(src.size)
        val n = LZ4.decode(encoded, 0, encoded.size, decoded, 0, decoded.size)
        assertEquals(src.size, n)
        assertContentEquals(src, decoded)
    }

    @Test
    fun lz4RoundTripSmallAndEmpty() {
        // 边界：空、1 字节、不足一个序列的短数据
        for (size in intArrayOf(0, 1, 2, 5, 13, 64)) {
            val src = ByteArray(size) { (it * 7 + 3).toByte() }
            val encoded = LZ4.encode(src)
            val decoded = ByteArray(src.size)
            val n = LZ4.decode(encoded, 0, encoded.size, decoded, 0, decoded.size)
            assertEquals(size, n, "size=$size 长度不符")
            assertContentEquals(src, decoded, "size=$size 内容不符")
        }
    }

    @Test
    fun lz4RoundTripTextLike() {
        // 模拟真实场景：文本流（词重复但非整块重复）
        val words = listOf("CABACIterator", "m_VertexData", "SerializedFile", "00000000")
        val sb = StringBuilder()
        repeat(5000) { sb.append(words[it % words.size]).append('\n') }
        val src = sb.toString().toByteArray(Charsets.UTF_8)
        val encoded = LZ4.encode(src)
        val decoded = ByteArray(src.size)
        val n = LZ4.decode(encoded, 0, encoded.size, decoded, 0, decoded.size)
        assertEquals(src.size, n)
        assertContentEquals(src, decoded)
    }

    // ---------- 纹理编码（替换链路的编码基础） ----------

    @Test
    fun encodeRgba32SingleMip() {
        val w = 8; val h = 8
        val pixels = IntArray(w * h) { 0xFF0000FF.toInt() } // 不透明白蓝
        val encoded = TextureEncoder.encode(pixels, w, h, TextureFormat.RGBA32, 1)
        assertNotNull(encoded, "RGBA32 必可编码: ${TextureEncoder.lastError}")
        assertEquals(w * h * 4, encoded.size, "RGBA32 每像素 4 字节")
        // 第一个像素：RGBA 字节序 R=0x00 G=0x00 B=0xFF A=0xFF
        assertEquals(0x00, encoded[0].toInt() and 0xFF)
        assertEquals(0x00, encoded[1].toInt() and 0xFF)
        assertEquals(0xFF, encoded[2].toInt() and 0xFF)
        assertEquals(0xFF, encoded[3].toInt() and 0xFF)
    }

    @Test
    fun encodeRgba32MipChain() {
        val w = 64; val h = 32
        val pixels = IntArray(w * h) { 0xFF112233.toInt() }
        val mips = 4
        val encoded = TextureEncoder.encode(pixels, w, h, TextureFormat.RGBA32, mips)
        assertNotNull(encoded)
        // 64*32 + 32*16 + 16*8 + 8*4 = 2048+512+128+32 = 2720 像素 * 4B
        val expectedPixels = 64 * 32 + 32 * 16 + 16 * 8 + 8 * 4
        assertEquals(expectedPixels * 4, encoded.size, "mip 链字节数应逐级累加")
    }

    @Test
    fun encodeRgba32MipChainOddSize() {
        // 非二次幂尺寸：mip 递减时最小钳制为 1
        val w = 5; val h = 3
        val pixels = IntArray(w * h) { 0xFF405060.toInt() }
        val encoded = TextureEncoder.encode(pixels, w, h, TextureFormat.RGBA32, 8)
        assertNotNull(encoded)
        // 级数: 5x3, 2x1, 1x1 → 之后不再生成（都是 1x1 停止条件 w>1||h>1）
        val expectedPixels = 5 * 3 + 2 * 1 + 1 * 1
        assertEquals(expectedPixels * 4, encoded.size)
    }

    @Test
    fun encodeDxt1BlockAligned() {
        val w = 16; val h = 16
        val pixels = IntArray(w * h) { i ->
            if (i % 2 == 0) 0xFFFF0000.toInt() else 0xFF00FF00.toInt()
        }
        val encoded = TextureEncoder.encode(pixels, w, h, TextureFormat.DXT1, 1)
        assertNotNull(encoded, "DXT1 必可编码: ${TextureEncoder.lastError}")
        // DXT1 每块 8 字节，16x16 = 4x4 块 = 32 字节
        assertEquals(8 * (w / 4) * (h / 4), encoded.size)
    }

    @Test
    fun encodeDxt5BlockAligned() {
        val w = 8; val h = 8
        val pixels = IntArray(w * h) { 0x80402010.toInt() }
        val encoded = TextureEncoder.encode(pixels, w, h, TextureFormat.DXT5, 1)
        assertNotNull(encoded)
        // DXT5 每块 16 字节，8x8 = 2x2 块 = 64 字节
        assertEquals(16 * (w / 4) * (h / 4), encoded.size)
    }

    @Test
    fun encodeRgb565UncompressedFormat() {
        val w = 4; val h = 4
        val pixels = IntArray(w * h) { 0xFF00FF00.toInt() }
        val encoded = TextureEncoder.encode(pixels, w, h, TextureFormat.RGB565, 1)
        assertNotNull(encoded)
        assertEquals(w * h * 2, encoded.size, "RGB565 每像素 2 字节")
    }

    @Test
    fun encodeAstcAllFootprints() {
        // TextureEncoder → AstcCodec 集成：4 种块尺寸，每块 16 字节
        val w = 16; val h = 16
        val pixels = IntArray(w * h) { 0xFF336699.toInt() }
        val cases = listOf(
            4 to TextureFormat.ASTC_RGBA_4x4,
            5 to TextureFormat.ASTC_RGB_5x5,
            6 to TextureFormat.ASTC_RGBA_6x6,
            8 to TextureFormat.ASTC_RGBA_8x8,
        )
        for ((fp, fmt) in cases) {
            val encoded = TextureEncoder.encode(pixels, w, h, fmt, 1)
            assertNotNull(encoded, "ASTC ${fp}x$fp 必可编码: ${TextureEncoder.lastError}")
            val blocks = ((w + fp - 1) / fp) * ((h + fp - 1) / fp)
            assertEquals(16 * blocks, encoded.size, "ASTC ${fp}x$fp 尺寸（16 字节/块）")
        }
    }

    @Test
    fun nonEncodableFormatReturnsNull() {
        val pixels = IntArray(16) { 0xFFFFFFFF.toInt() }
        // BC7 无软件编码器 → 返回 null（替换流程回退 RGBA32）
        val encoded = TextureEncoder.encode(pixels, 4, 4, TextureFormat.BC7, 1)
        assertEquals(null, encoded, "BC7 编码器未实现应返回 null")
        assertTrue(TextureEncoder.lastError != null, "应记录错误原因")
    }

    @Test
    fun isFormatEncodableMatrix() {
        // 可编码
        assertTrue(TextureEncoder.isFormatEncodable(TextureFormat.RGBA32))
        assertTrue(TextureEncoder.isFormatEncodable(TextureFormat.ARGB32))
        assertTrue(TextureEncoder.isFormatEncodable(TextureFormat.RGB24))
        assertTrue(TextureEncoder.isFormatEncodable(TextureFormat.BGRA32))
        assertTrue(TextureEncoder.isFormatEncodable(TextureFormat.Alpha8))
        assertTrue(TextureEncoder.isFormatEncodable(TextureFormat.R8))
        assertTrue(TextureEncoder.isFormatEncodable(TextureFormat.RGB565))
        assertTrue(TextureEncoder.isFormatEncodable(TextureFormat.ARGB4444))
        assertTrue(TextureEncoder.isFormatEncodable(TextureFormat.DXT1))
        assertTrue(TextureEncoder.isFormatEncodable(TextureFormat.DXT5))
        // ASTC 已提供软件编码器（4x4/5x5/6x6/8x8）
        assertTrue(TextureEncoder.isFormatEncodable(TextureFormat.ASTC_RGBA_4x4))
        assertTrue(TextureEncoder.isFormatEncodable(TextureFormat.ASTC_RGB_6x6))
        // 不可编码 → 替换时回退 RGBA32
        assertTrue(!TextureEncoder.isFormatEncodable(TextureFormat.BC7))
        assertTrue(!TextureEncoder.isFormatEncodable(TextureFormat.ETC2_RGBA8))
        assertTrue(!TextureEncoder.isFormatEncodable(TextureFormat.DXT1Crunched))
    }

    @Test
    fun invalidPixelBufferRejected() {
        val encoded = TextureEncoder.encode(IntArray(4), 8, 8, TextureFormat.RGBA32, 1)
        assertEquals(null, encoded, "像素数不足应拒绝")
    }
}

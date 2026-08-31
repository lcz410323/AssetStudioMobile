package com.assetstudio.mobile

import com.assetstudio.mobile.replace.AstcCodec
import kotlin.math.log10
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * ASTC 编码器回环（roundtrip）单元测试：encode → 自带软件解码器 decode → PSNR。
 *
 * 位布局已用独立解码器 texture2ddecoder 交叉验证（28/28 通过，
 * 见 astc_ref/test_cross_validate.py）；本测试验证 Kotlin 移植版：
 * 1. 输出尺寸 = ceil(w/fp) * ceil(h/fp) * 16
 * 2. 纯色块走 void-extent，位精确还原
 * 3. 渐变/噪声/边缘尺寸/单块的质量门槛（与 Python 参考一致）
 */
class AstcCodecTest {

    private val thresholds = mapOf(
        4 to mapOf("grad" to 38.0, "grad2" to 34.0, "diag" to 34.0, "noise" to 9.0, "edge" to 34.0, "single" to 30.0),
        5 to mapOf("grad" to 32.0, "grad2" to 28.0, "diag" to 28.0, "noise" to 9.0, "edge" to 28.0, "single" to 26.0),
        6 to mapOf("grad" to 28.0, "grad2" to 25.0, "diag" to 25.0, "noise" to 9.0, "edge" to 25.0, "single" to 24.0),
        8 to mapOf("grad" to 22.0, "grad2" to 18.0, "diag" to 18.0, "noise" to 9.0, "edge" to 18.0, "single" to 16.0),
    )

    // ---------- 尺寸 ----------

    @Test
    fun outputSizeMatchesBlockCount() {
        for (fp in intArrayOf(4, 5, 6, 8)) {
            val w = 33; val h = 17
            val data = AstcCodec.encode(solid(0xFF3C7DA0.toInt(), w * h), w, h, fp)
            val expected = ((w + fp - 1) / fp) * ((h + fp - 1) / fp) * 16
            assertEquals(expected, data.size, "footprint=$fp 尺寸不符")
        }
    }

    // ---------- 纯色（void extent，位精确） ----------

    @Test
    fun solidColorRoundTripsExactly() {
        for (fp in intArrayOf(4, 5, 6, 8)) {
            val w = 32; val h = 24
            val color = 0xFF3C7DA0.toInt()
            val src = solid(color, w * h)
            val decoded = AstcCodec.decode(AstcCodec.encode(src, w, h, fp), w, h, fp)
            for (i in src.indices) {
                assertEquals(src[i], decoded[i], "footprint=$fp 像素 $i 应位精确（void extent）")
            }
        }
    }

    @Test
    fun solidColorWithAlphaRoundTripsExactly() {
        val w = 20; val h = 10
        val color = (0x80 shl 24) or (0x12 shl 16) or (0xAB shl 8) or 0x7F
        val src = solid(color, w * h)
        val decoded = AstcCodec.decode(AstcCodec.encode(src, w, h, 6), w, h, 6)
        for (i in src.indices) assertEquals(src[i], decoded[i])
    }

    // ---------- 渐变 / 噪声 / 边缘 ----------

    @Test
    fun gradientsNoiseEdgeRoundTrip() {
        val rnd = Random(20260824)
        for (fp in intArrayOf(4, 5, 6, 8)) {
            val th = thresholds[fp]!!
            val w = 32; val h = 24

            // 水平渐变
            val grad = IntArray(w * h) { i ->
                val x = i % w
                val t = x.toDouble() / (w - 1)
                val r = (255 * t).toInt(); val g = (200 * (1 - t)).toInt(); val b = (128 * t + 60).toInt()
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            assertPsnr("水平渐变 fp=$fp", grad, w, h, fp, th["grad"]!!)

            // 垂直渐变 + alpha
            val grad2 = IntArray(w * h) { i ->
                val y = i / w
                val t = y.toDouble() / (h - 1)
                val r = (60 * t + 30).toInt(); val g = (180 * t).toInt()
                val b = (255 - 100 * t).toInt(); val a = (255 * t).toInt()
                (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            assertPsnr("垂直渐变 fp=$fp", grad2, w, h, fp, th["grad2"]!!)

            // 对角渐变
            val diag = IntArray(w * h) { i ->
                val x = i % w; val y = i / w
                val t = (x + y).toDouble() / (w + h - 2)
                val c = (255 * t).toInt()
                (0xFF shl 24) or (c shl 16) or (c shl 8) or (255 - c)
            }
            assertPsnr("对角渐变 fp=$fp", diag, w, h, fp, th["diag"]!!)

            // 随机噪声（布局校验）
            val noise = IntArray(w * h) { rnd.nextInt() or 0xFF000000.toInt() }
            assertPsnr("噪声 fp=$fp", noise, w, h, fp, th["noise"]!!)

            // 边缘尺寸
            val ew = 33; val eh = 17
            val edge = IntArray(ew * eh) { i ->
                val x = i % ew
                val t = x.toDouble() / (ew - 1)
                (0xFF shl 24) or ((255 * t).toInt() shl 16) or ((120 * (1 - t)).toInt() shl 8) or 80
            }
            assertPsnr("边缘 fp=$fp", edge, ew, eh, fp, th["edge"]!!)

            // 单块（1D 对角渐变）
            val sw = fp; val sh = fp
            val single = IntArray(sw * sh) { i ->
                val x = i % sw; val y = i / sw
                val t = (x + y).toDouble() / (2 * sw - 2)
                (0xFF shl 24) or ((255 * t).toInt() shl 16) or ((255 * (1 - t)).toInt() shl 8) or 128
            }
            assertPsnr("单块 fp=$fp", single, sw, sh, fp, th["single"]!!)
        }
    }

    // ---------- 1x1 / 极小图 ----------

    @Test
    fun tinyImageDoesNotCrash() {
        for (fp in intArrayOf(4, 5, 6, 8)) {
            val src = intArrayOf(0xFF102030.toInt(), 0xFFA0B0C0.toInt())
            val data = AstcCodec.encode(src, 2, 1, fp)
            assertEquals(16, data.size)
            val decoded = AstcCodec.decode(data, 2, 1, fp)
            assertEquals(2, decoded.size)
        }
    }

    // ---------- 工具 ----------

    private fun solid(color: Int, count: Int) = IntArray(count) { color }

    private fun assertPsnr(name: String, src: IntArray, w: Int, h: Int, fp: Int, minPsnr: Double) {
        val data = AstcCodec.encode(src, w, h, fp)
        val decoded = AstcCodec.decode(data, w, h, fp)
        var mse = 0.0
        for (i in src.indices) {
            for (shift in intArrayOf(16, 8, 0, 24)) {
                val d = ((src[i] shr shift) and 0xFF) - ((decoded[i] shr shift) and 0xFF)
                mse += (d * d).toDouble()
            }
        }
        mse /= src.size * 4
        val psnr = if (mse == 0.0) Double.POSITIVE_INFINITY else 10 * log10(255.0 * 255.0 / mse)
        assertTrue(
            psnr >= minPsnr,
            "$name PSNR=%.2f < %.2f".format(psnr, minPsnr)
        )
    }
}

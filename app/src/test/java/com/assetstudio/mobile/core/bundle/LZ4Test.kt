package com.assetstudio.mobile.core.bundle

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

/**
 * LZ4 block 编解码 roundtrip 测试。
 */
class LZ4Test {

    @Test
    fun `roundtrip 空数据`() {
        val src = ByteArray(0)
        val compressed = LZ4.encode(src)
        assertEquals(1, compressed.size) // 只有一个 token
        val dst = ByteArray(0)
        val n = LZ4.decode(compressed, 0, compressed.size, dst, 0, 0)
        assertEquals(0, n)
    }

    @Test
    fun `roundtrip 极短数据`() {
        for (len in intArrayOf(1, 2, 5, 17)) {
            val src = ByteArray(len) { (it * 31 + 7).toByte() }
            val compressed = LZ4.encode(src)
            val dst = ByteArray(len)
            val n = LZ4.decode(compressed, 0, compressed.size, dst, 0, dst.size)
            assertEquals("len=$len", len, n)
            assertArrayEquals("len=$len", src, dst)
        }
    }

    @Test
    fun `roundtrip 随机数据(不可压缩)`() {
        val random = Random(12345)
        for (size in intArrayOf(64, 255, 256, 4096, 70000, 200000)) {
            val src = ByteArray(size) { random.nextInt().toByte() }
            val compressed = LZ4.encode(src)
            val dst = ByteArray(size)
            val n = LZ4.decode(compressed, 0, compressed.size, dst, 0, dst.size)
            assertEquals("size=$size", size, n)
            assertArrayEquals("size=$size", src, dst)
        }
    }

    @Test
    fun `roundtrip 重复模式数据(高度可压缩)`() {
        // 单字节重复
        val zeros = ByteArray(100000) { 0 }
        roundtrip(zeros)
        // 周期性模式
        val pattern = ByteArray(300000)
        val unit = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        for (i in pattern.indices) pattern[i] = unit[i % unit.size]
        roundtrip(pattern)
        // 长匹配 + 尾部随机
        val mixed = ByteArray(150000)
        val random = Random(99)
        for (i in 0 until 1000) mixed[i] = random.nextInt().toByte()
        for (i in 1000 until 140000) mixed[i] = mixed[i - 1000]
        for (i in 140000 until mixed.size) mixed[i] = random.nextInt().toByte()
        roundtrip(mixed)
    }

    @Test
    fun `roundtrip 部分偏移编码`() {
        // srcPos/srcLen/dstPos 分段参数
        val random = Random(7)
        val full = ByteArray(5000) { (random.nextInt(256)).toByte() }
        for (i in 0 until 2500) full[i] = full[i % 64]
        val srcPos = 100
        val srcLen = 4000
        val compressed = LZ4.encode(full, srcPos, srcLen)
        val dst = ByteArray(srcLen + 32) // 故意留出头尾空间
        val n = LZ4.decode(compressed, 0, compressed.size, dst, 16, srcLen)
        assertEquals(srcLen, n)
        assertArrayEquals(
            full.copyOfRange(srcPos, srcPos + srcLen),
            dst.copyOfRange(16, 16 + srcLen)
        )
    }

    @Test
    fun `压缩率检查 - 重复数据应显著小于原始`() {
        val src = ByteArray(100000) { 0 }
        val compressed = LZ4.encode(src)
        // 全零数据应压缩到几百字节以内
        assert(compressed.size < 500) { "compressed=${compressed.size}" }
    }

    @Test
    fun `解码错误返回 -1`() {
        // 空 dst 但数据含字面量
        val compressed = LZ4.encode(ByteArray(10) { it.toByte() })
        assertEquals(-1, LZ4.decode(compressed, 0, compressed.size, ByteArray(5), 0, 5))
        // 非法偏移（offset=0）
        assertEquals(-1, LZ4.decode(byteArrayOf(0x00, 0x00, 0x00), 0, 3, ByteArray(8), 0, 8))
        // src 越界
        assertEquals(-1, LZ4.decode(compressed, 0, compressed.size + 1, ByteArray(10), 0, 10))
    }

    private fun roundtrip(src: ByteArray) {
        val compressed = LZ4.encode(src)
        val dst = ByteArray(src.size)
        val n = LZ4.decode(compressed, 0, compressed.size, dst, 0, dst.size)
        assertEquals(src.size, n)
        assertArrayEquals(src, dst)
    }
}

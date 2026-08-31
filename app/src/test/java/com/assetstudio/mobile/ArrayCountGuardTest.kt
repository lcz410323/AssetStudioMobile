package com.assetstudio.mobile

import com.assetstudio.mobile.core.io.EndianBinaryReader
import com.assetstudio.mobile.core.io.EndianType
import java.io.EOFException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/*
 * 回归测试：修复线上闪退
 *   java.lang.OutOfMemoryError: Failed to allocate a 4261412880 byte allocation
 *   at EndianBinaryReader.readSingleArray (HandPose.m_DoFArray)
 *
 * 解析位置错位时读到垃圾长度（0xFE000000 ≈ 4GB），旧实现直接分配导致 OOM 闪退。
 * 现所有数组长度前缀均经 readArrayCount 校验：非法长度抛 EOFException，
 * 由 readAssets 的 catch(Throwable) 捕获后跳过该对象，其余资产继续加载。
 */
class ArrayCountGuardTest {

    /** 构造一个 BigEndian（Unity 序列化默认）的小缓冲，写入 4 字节 int 后接少量数据 */
    private fun readerWith(vararg ints: Int, tail: Int = 16): EndianBinaryReader {
        val buf = ByteArray(ints.size * 4 + tail)
        val r = EndianBinaryReader(buf, EndianType.BigEndian)
        for (v in ints) {
            buf[r.position] = (v ushr 24).toByte()
            buf[r.position + 1] = (v ushr 16).toByte()
            buf[r.position + 2] = (v ushr 8).toByte()
            buf[r.position + 3] = v.toByte()
            r.position += 4
        }
        r.position = 0
        return r
    }

    @Test
    fun garbageLengthThrowsInsteadOfOom() {
        // 崩溃现场：0xFE000000 = 4261412880（正是一次闪退日志里的分配大小）
        val reader = readerWith(0xFE000000.toInt())
        assertFailsWith<EOFException>("垃圾长度必须抛 EOFException 而非分配 4GB") {
            reader.readSingleArray()
        }
    }

    @Test
    fun negativeLengthRejected() {
        // readInt32 读到 -1（0xFFFFFFFF）同样必须拦截
        val reader = readerWith(-1)
        assertFailsWith<EOFException> { reader.readSingleArray() }
    }

    @Test
    fun allArrayReadersGuarded() {
        // 各类数组读取器对垃圾长度的统一防护
        val types = listOf(
            { r: EndianBinaryReader -> r.readBooleanArray() },
            { r: EndianBinaryReader -> r.readInt16Array() },
            { r: EndianBinaryReader -> r.readUInt16Array() },
            { r: EndianBinaryReader -> r.readInt32Array() },
            { r: EndianBinaryReader -> r.readUInt32Array() },
            { r: EndianBinaryReader -> r.readInt64Array() },
            { r: EndianBinaryReader -> r.readSingleArray() },
            { r: EndianBinaryReader -> r.readDoubleArray() },
            { r: EndianBinaryReader -> r.readStringArray() },
            { r: EndianBinaryReader -> r.readVector2Array() },
            { r: EndianBinaryReader -> r.readVector4Array() },
            { r: EndianBinaryReader -> r.readMatrixArray() },
            { r: EndianBinaryReader -> r.readUInt8Array() }
        )
        for (read in types) {
            val reader = readerWith(0x7FFFFFF0) // ~2G 元素
            assertFailsWith<EOFException>("该数组读取器未做长度校验") {
                read(reader)
            }
        }
    }

    @Test
    fun legitLengthStillPasses() {
        // 合法数据不受影响：2 个 float（8 字节数据 + 尾部富余）
        val reader = readerWith(2, 0x3F800000, 0x40000000)
        val arr = reader.readSingleArray()
        assertEquals(2, arr.size)
        assertEquals(1.0f, arr[0])
        assertEquals(2.0f, arr[1])
    }

    @Test
    fun boundaryExactRemainingPasses() {
        // count * elemSize == remaining 的边界应放行（合法最大数组）
        // buffer: 8 字节 = 长度(4) + 1 个 float(4)，无尾部 → remaining 读长度前为 8
        val buf = ByteArray(8)
        buf[0] = 0; buf[1] = 0; buf[2] = 0; buf[3] = 1 // length = 1
        val reader = EndianBinaryReader(buf, EndianType.BigEndian)
        assertEquals(1, reader.readArrayCount(4))
    }

    @Test
    fun boundaryOverflowRejected() {
        // count * elemSize > remaining 一律拒绝
        val buf = ByteArray(7) // 长度(4) + 3 字节，不够一个 float
        buf[3] = 1 // length = 1
        val reader = EndianBinaryReader(buf, EndianType.BigEndian)
        assertFailsWith<EOFException> { reader.readArrayCount(4) }
        assertTrue(true)
    }
}

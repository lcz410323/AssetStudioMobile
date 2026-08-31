package com.assetstudio.mobile.core.io

import java.io.EOFException

/**
 * AssetStudio EndianBinaryReader 的 Kotlin 移植版。
 * 直接在 ByteArray 上工作（无流开销），支持大小端切换、对齐、字符串与数组读取。
 *
 * 类型映射约定（与 C# 对应）：
 * - ReadUInt8  -> Int (0..255)
 * - ReadInt8   -> Byte
 * - ReadInt16  -> Short
 * - ReadUInt16 -> Int (0..65535)
 * - ReadInt32  -> Int
 * - ReadUInt32 -> Long (0..4294967295)
 * - ReadInt64/ReadUInt64 -> Long
 */
open class EndianBinaryReader(
    val buffer: ByteArray,
    endian: EndianType = EndianType.BigEndian,
    startPosition: Int = 0
) {
    var endian: EndianType = endian

    /** 当前读取位置（字节偏移） */
    var position: Int = startPosition
        set(value) {
            require(value in 0..buffer.size) { "position 越界: $value, size=${buffer.size}" }
            field = value
        }

    val length: Int get() = buffer.size
    val remaining: Int get() = buffer.size - position

    private fun ensure(count: Int) {
        if (count < 0 || position + count > buffer.size) {
            throw EOFException("读取越界: position=$position, count=$count, size=${buffer.size}")
        }
    }

    fun readInt8(): Byte {
        ensure(1)
        return buffer[position++]
    }

    fun readUInt8(): Int {
        ensure(1)
        return buffer[position++].toInt() and 0xFF
    }

    fun readBoolean(): Boolean {
        ensure(1)
        return buffer[position++].toInt() != 0
    }

    fun readInt16(): Short {
        ensure(2)
        val b0 = buffer[position++].toInt() and 0xFF
        val b1 = buffer[position++].toInt() and 0xFF
        return if (endian == EndianType.BigEndian) {
            ((b0 shl 8) or b1).toShort()
        } else {
            ((b1 shl 8) or b0).toShort()
        }
    }

    fun readUInt16(): Int {
        ensure(2)
        val b0 = buffer[position++].toInt() and 0xFF
        val b1 = buffer[position++].toInt() and 0xFF
        return if (endian == EndianType.BigEndian) {
            (b0 shl 8) or b1
        } else {
            (b1 shl 8) or b0
        }
    }

    fun readInt32(): Int {
        ensure(4)
        val b0 = buffer[position++].toInt() and 0xFF
        val b1 = buffer[position++].toInt() and 0xFF
        val b2 = buffer[position++].toInt() and 0xFF
        val b3 = buffer[position++].toInt() and 0xFF
        return if (endian == EndianType.BigEndian) {
            (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        } else {
            (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
        }
    }

    fun readUInt32(): Long = readInt32().toLong() and 0xFFFFFFFFL

    fun readInt64(): Long {
        ensure(8)
        var result = 0L
        if (endian == EndianType.BigEndian) {
            for (i in 0 until 8) result = (result shl 8) or (buffer[position + i].toLong() and 0xFF)
        } else {
            for (i in 7 downTo 0) result = (result shl 8) or (buffer[position + i].toLong() and 0xFF)
        }
        position += 8
        return result
    }

    fun readUInt64(): Long = readInt64()

    fun readSingle(): Float = Float.fromBits(readInt32())

    fun readDouble(): Double {
        return Double.fromBits(readInt64())
    }

    fun readBytes(count: Int): ByteArray {
        if (count < 0) throw IllegalArgumentException("count < 0")
        ensure(count)
        val result = buffer.copyOfRange(position, position + count)
        position += count
        return result
    }

    /** 跳过 n 字节 */
    fun skipBytes(n: Int) {
        ensure(n)
        position += n
    }

    fun readStringToNull(maxLength: Int = 32767): String {
        val start = position
        var count = 0
        while (position < buffer.size && count < maxLength) {
            val b = buffer[position++]
            if (b.toInt() == 0) break
            count++
        }
        return String(buffer, start, count, Charsets.UTF_8)
    }

    /** 读取带长度前缀 + 4 字节对齐的字符串（Unity 标准字符串） */
    fun readAlignedString(): String {
        val length = readInt32()
        if (length > 0 && length <= buffer.size - position) {
            val stringData = readBytes(length)
            alignStream(4)
            return String(stringData, Charsets.UTF_8)
        }
        return ""
    }

    fun alignStream(alignment: Int = 4) {
        val mod = position % alignment
        if (mod != 0) {
            position += alignment - mod
            if (position > buffer.size) position = buffer.size
        }
    }

    // ============ 数组读取（长度前缀为 int32） ============

    /**
     * 读取数组长度前缀并做合法性校验。
     *
     * 解析位置错位时会读到垃圾长度（如 0xFE000000 ≈ 4GB），
     * 直接分配会导致 Android 端 OutOfMemoryError 闪退。
     * 这里强制要求 count * elemSize 不超过剩余字节数（合法数组必然满足，
     * 每个元素至少占 elemSize 字节），非法即抛 EOFException，由上层
     * readAssets 捕获后跳过该对象，保证其他资产可继续加载。
     */
    fun readArrayCount(elemSize: Int = 1): Int {
        val count = readInt32()
        if (count < 0 || count.toLong() * elemSize > remaining) {
            throw EOFException(
                "非法数组长度: $count (elemSize=$elemSize, remaining=$remaining, position=$position)"
            )
        }
        return count
    }

    fun readBooleanArray(): BooleanArray {
        val length = readArrayCount(1)
        return BooleanArray(length) { readBoolean() }
    }

    fun readUInt8Array(): ByteArray = readBytes(readArrayCount(1))

    fun readInt16Array(): ShortArray {
        val length = readArrayCount(2)
        return ShortArray(length) { readInt16() }
    }

    fun readUInt16Array(): IntArray {
        val length = readArrayCount(2)
        return IntArray(length) { readUInt16() }
    }

    fun readInt32Array(): IntArray {
        val length = readArrayCount(4)
        return IntArray(length) { readInt32() }
    }

    fun readInt32Array(length: Int): IntArray = IntArray(length) { readInt32() }

    fun readUInt32Array(): LongArray {
        val length = readArrayCount(4)
        return LongArray(length) { readUInt32() }
    }

    fun readInt64Array(): LongArray {
        val length = readArrayCount(8)
        return LongArray(length) { readInt64() }
    }

    fun readSingleArray(): FloatArray {
        val length = readArrayCount(4)
        return FloatArray(length) { readSingle() }
    }

    fun readSingleArray(length: Int): FloatArray = FloatArray(length) { readSingle() }

    fun readDoubleArray(): DoubleArray {
        val length = readArrayCount(8)
        return DoubleArray(length) { readDouble() }
    }

    fun readStringArray(): Array<String> {
        // 每个 aligned string 至少 4 字节长度前缀
        val length = readArrayCount(4)
        return Array(length) { readAlignedString() }
    }

    // ============ 数学类型读取 ============

    fun readVector2(): com.assetstudio.mobile.core.math.Vector2 = com.assetstudio.mobile.core.math.Vector2(readSingle(), readSingle())

    fun readVector3(): com.assetstudio.mobile.core.math.Vector3 = com.assetstudio.mobile.core.math.Vector3(readSingle(), readSingle(), readSingle())

    fun readVector4(): com.assetstudio.mobile.core.math.Vector4 = com.assetstudio.mobile.core.math.Vector4(readSingle(), readSingle(), readSingle(), readSingle())

    fun readQuaternion(): com.assetstudio.mobile.core.math.Quaternion =
        com.assetstudio.mobile.core.math.Quaternion(readSingle(), readSingle(), readSingle(), readSingle())

    fun readColor4(): com.assetstudio.mobile.core.math.Color =
        com.assetstudio.mobile.core.math.Color(readSingle(), readSingle(), readSingle(), readSingle())

    fun readMatrix(): com.assetstudio.mobile.core.math.Matrix4x4 {
        val values = readSingleArray(16)
        return com.assetstudio.mobile.core.math.Matrix4x4(values)
    }

    fun readColor4Array(): Array<com.assetstudio.mobile.core.math.Color> {
        val length = readArrayCount(16)
        return Array(length) { readColor4() }
    }

    fun readVector2Array(): Array<com.assetstudio.mobile.core.math.Vector2> {
        val length = readArrayCount(8)
        return Array(length) { readVector2() }
    }

    fun readVector4Array(): Array<com.assetstudio.mobile.core.math.Vector4> {
        val length = readArrayCount(16)
        return Array(length) { readVector4() }
    }

    fun readMatrixArray(): Array<com.assetstudio.mobile.core.math.Matrix4x4> {
        val length = readArrayCount(64)
        return Array(length) { readMatrix() }
    }
}

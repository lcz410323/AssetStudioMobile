package com.assetstudio.mobile.core.classes

/*
 * 来源: AssetStudio/Classes/AnimationClip.cs 中的 PackedFloatVector / PackedIntVector / PackedQuatVector
 * （Mesh.cs 与 AnimationClip.cs 共用，独立成文件）
 */

import com.assetstudio.mobile.core.ObjectReader
import com.assetstudio.mobile.core.math.Quaternion

class PackedFloatVector(reader: ObjectReader) {
    var m_NumItems: Long = 0 //uint
    val m_Range: Float
    val m_Start: Float
    val m_Data: ByteArray
    val m_BitSize: Int //byte

    init {
        m_NumItems = reader.readUInt32()
        m_Range = reader.readSingle()
        m_Start = reader.readSingle()

        val numData = reader.readInt32()
        m_Data = reader.readBytes(numData)
        reader.alignStream()

        m_BitSize = reader.readUInt8()
        reader.alignStream()
    }

    fun unpackFloats(itemCountInChunk: Int, chunkStride: Int, start: Int = 0, numChunksParam: Int = -1): FloatArray {
        var bitPos = m_BitSize * start
        var indexPos = bitPos / 8
        bitPos %= 8

        val scale = 1.0f / m_Range
        val numChunks = if (numChunksParam == -1) m_NumItems.toInt() / itemCountInChunk else numChunksParam
        val end = chunkStride * numChunks / 4
        val data = ArrayList<Float>()
        var index = 0
        while (index != end) {
            for (i in 0 until itemCountInChunk) {
                var x = 0L

                var bits = 0
                while (bits < m_BitSize) {
                    val b = m_Data[indexPos].toLong() and 0xFF
                    x = x or ((b shr bitPos) shl bits)
                    val num = minOf(m_BitSize - bits, 8 - bitPos)
                    bitPos += num
                    bits += num
                    if (bitPos == 8) {
                        indexPos++
                        bitPos = 0
                    }
                }
                x = x and ((1L shl m_BitSize) - 1L)
                data.add(x.toFloat() / (scale * ((1 shl m_BitSize) - 1)) + m_Start)
            }
            index += chunkStride / 4
        }

        return data.toFloatArray()
    }
}

class PackedIntVector(reader: ObjectReader) {
    var m_NumItems: Long = 0 //uint
    val m_Data: ByteArray
    var m_BitSize: Int //byte

    init {
        m_NumItems = reader.readUInt32()

        val numData = reader.readInt32()
        m_Data = reader.readBytes(numData)
        reader.alignStream()

        m_BitSize = reader.readUInt8()
        reader.alignStream()
    }

    fun unpackInts(): IntArray {
        val data = IntArray(m_NumItems.toInt())
        var indexPos = 0
        var bitPos = 0
        for (i in data.indices) {
            var bits = 0
            data[i] = 0
            while (bits < m_BitSize) {
                val b = m_Data[indexPos].toInt() and 0xFF
                data[i] = data[i] or ((b shr bitPos) shl bits)
                val num = minOf(m_BitSize - bits, 8 - bitPos)
                bitPos += num
                bits += num
                if (bitPos == 8) {
                    indexPos++
                    bitPos = 0
                }
            }
            data[i] = data[i] and ((1 shl m_BitSize) - 1)
        }
        return data
    }
}

class PackedQuatVector(reader: ObjectReader) {
    val m_NumItems: Long //uint
    val m_Data: ByteArray

    init {
        m_NumItems = reader.readUInt32()

        val numData = reader.readInt32()
        m_Data = reader.readBytes(numData)

        reader.alignStream()
    }

    fun unpackQuats(): Array<Quaternion> {
        val data = Array(m_NumItems.toInt()) { Quaternion() }
        var indexPos = 0
        var bitPos = 0

        for (i in data.indices) {
            var flags = 0

            var bits = 0
            while (bits < 3) {
                val b = m_Data[indexPos].toInt() and 0xFF
                flags = flags or ((b shr bitPos) shl bits)
                val num = minOf(3 - bits, 8 - bitPos)
                bitPos += num
                bits += num
                if (bitPos == 8) {
                    indexPos++
                    bitPos = 0
                }
            }
            flags = flags and 7

            val q = Quaternion()
            var sum = 0f
            for (j in 0 until 4) {
                if (flags and 3 != j) {
                    val bitSize = if (((flags and 3) + 1) % 4 == j) 9 else 10
                    var x = 0L

                    bits = 0
                    while (bits < bitSize) {
                        val b = m_Data[indexPos].toLong() and 0xFF
                        x = x or ((b shr bitPos) shl bits)
                        val num = minOf(bitSize - bits, 8 - bitPos)
                        bitPos += num
                        bits += num
                        if (bitPos == 8) {
                            indexPos++
                            bitPos = 0
                        }
                    }
                    x = x and ((1L shl bitSize) - 1L)
                    val v = x.toFloat() / (0.5f * ((1 shl bitSize) - 1)) - 1f
                    q[j] = v
                    sum += v * v
                }
            }

            val lastComponent = flags and 3
            q[lastComponent] = kotlin.math.sqrt(1 - sum)
            if (flags and 4 != 0) {
                q[lastComponent] = -q[lastComponent]
            }
            data[i] = q
        }

        return data
    }

    private operator fun Quaternion.get(j: Int): Float = when (j) {
        0 -> this.x
        1 -> this.y
        2 -> this.z
        else -> this.w
    }

    private operator fun Quaternion.set(j: Int, value: Float) {
        when (j) {
            0 -> x = value
            1 -> y = value
            2 -> z = value
            3 -> w = value
        }
    }
}

package com.assetstudio.mobile.core.bundle.lzma

/**
 * 来自 7zip/Compress/RangeCoder/RangeCoder.cs（Decoder 部分）、
 * 7zip/Compress/RangeCoder/RangeCoderBit.cs（BitDecoder 部分）、
 * 7zip/Compress/RangeCoder/RangeCoderBitTree.cs（BitTreeDecoder 部分）、
 * 7zip/Compress/LZMA/LzmaBase.cs（常量与 State）。
 *
 * uint -> Int（保留 32 位位模式，乘法/加减法回绕语义与 C# unchecked uint 一致），
 * 无符号比较/移位统一用 ushr 与 Long 掩码辅助函数完成。
 */

/** 无符号比较：a < b（按 32 位无符号语义） */
internal fun uintLess(a: Int, b: Int): Boolean =
    (a.toLong() and 0xFFFFFFFFL) < (b.toLong() and 0xFFFFFFFFL)

/** LzmaBase.cs 的 Base 常量 */
internal object LzmaBase {
    const val K_NUM_REP_DISTANCES = 4
    const val K_NUM_STATES = 12

    const val K_NUM_POS_SLOT_BITS = 6
    const val K_NUM_LEN_TO_POS_STATES_BITS = 2
    const val K_NUM_LEN_TO_POS_STATES = 1 shl K_NUM_LEN_TO_POS_STATES_BITS

    const val K_MATCH_MIN_LEN = 2

    const val K_NUM_ALIGN_BITS = 4

    const val K_START_POS_MODEL_INDEX = 4
    const val K_END_POS_MODEL_INDEX = 14
    const val K_NUM_FULL_DISTANCES = 1 shl (K_END_POS_MODEL_INDEX / 2)

    const val K_NUM_POS_STATES_BITS_MAX = 4
    const val K_NUM_POS_STATES_MAX = 1 shl K_NUM_POS_STATES_BITS_MAX

    const val K_NUM_LOW_LEN_BITS = 3
    const val K_NUM_MID_LEN_BITS = 3
    const val K_NUM_HIGH_LEN_BITS = 8
    const val K_NUM_LOW_LEN_SYMBOLS = 1 shl K_NUM_LOW_LEN_BITS
    const val K_NUM_MID_LEN_SYMBOLS = 1 shl K_NUM_MID_LEN_BITS

    /** 对应 Base.GetLenToPosState(uint len) */
    fun getLenToPosState(len: Int): Int {
        val l = len - K_MATCH_MIN_LEN
        return if (l < K_NUM_LEN_TO_POS_STATES) l else K_NUM_LEN_TO_POS_STATES - 1
    }
}

/** LzmaBase.cs 的 State 结构 */
internal class LzmaState {
    var index: Int = 0

    fun init() {
        index = 0
    }

    fun updateChar() {
        index = if (index < 4) 0 else if (index < 10) index - 3 else index - 6
    }

    fun updateMatch() {
        index = if (index < 7) 7 else 10
    }

    fun updateRep() {
        index = if (index < 7) 8 else 11
    }

    fun updateShortRep() {
        index = if (index < 7) 9 else 11
    }

    fun isCharState(): Boolean = index < 7
}

/** RangeCoder.cs 的 Decoder（range/code 为 uint 位模式，存 Int） */
internal class RangeDecoder {

    var range: Int = 0
    var code: Int = 0
    var stream: InBuffer? = null

    fun init(stream: InBuffer) {
        this.stream = stream
        code = 0
        range = -1 // 0xFFFFFFFF
        for (i in 0 until 5) {
            code = (code shl 8) or stream.readByte()
        }
    }

    fun releaseStream() {
        stream = null
    }

    /** range（无符号）< 2^24 等价于高 8 位为 0 */
    private fun needNormalize(): Boolean = range ushr 24 == 0

    fun normalize() {
        while (needNormalize()) {
            code = (code shl 8) or (stream?.readByte() ?: 0xFF)
            range = range shl 8
        }
    }

    fun normalize2() {
        if (needNormalize()) {
            code = (code shl 8) or (stream?.readByte() ?: 0xFF)
            range = range shl 8
        }
    }

    fun decodeDirectBits(numTotalBits: Int): Int {
        var range = this.range
        var code = this.code
        var result = 0
        for (i in numTotalBits downTo 1) {
            range = range ushr 1
            val t = (code - range) ushr 31 // C# uint >> 31 的逻辑移位
            code -= range and (t - 1)
            result = (result shl 1) or (1 - t)
            if (range ushr 24 == 0) {
                code = (code shl 8) or (stream?.readByte() ?: 0xFF)
                range = range shl 8
            }
        }
        this.range = range
        this.code = code
        return result
    }

    fun decodeBit(size0: Int, numTotalBits: Int): Int {
        val newBound = (range ushr numTotalBits) * size0
        val symbol: Int
        if (uintLess(code, newBound)) {
            symbol = 0
            range = newBound
        } else {
            symbol = 1
            code -= newBound
            range -= newBound
        }
        normalize()
        return symbol
    }
}

/** RangeCoderBit.cs 的 BitDecoder */
internal class BitDecoder {
    var prob: Int = 0 // uint

    fun init() {
        prob = K_BIT_MODEL_TOTAL ushr 1
    }

    fun decode(rangeDecoder: RangeDecoder): Int {
        val newBound = (rangeDecoder.range ushr K_NUM_BIT_MODEL_TOTAL_BITS) * prob
        return if (uintLess(rangeDecoder.code, newBound)) {
            rangeDecoder.range = newBound
            prob += (K_BIT_MODEL_TOTAL - prob) ushr K_NUM_MOVE_BITS
            if (rangeDecoder.range ushr 24 == 0) {
                rangeDecoder.code = (rangeDecoder.code shl 8) or (rangeDecoder.stream?.readByte() ?: 0xFF)
                rangeDecoder.range = rangeDecoder.range shl 8
            }
            0
        } else {
            rangeDecoder.range -= newBound
            rangeDecoder.code -= newBound
            prob -= prob ushr K_NUM_MOVE_BITS
            if (rangeDecoder.range ushr 24 == 0) {
                rangeDecoder.code = (rangeDecoder.code shl 8) or (rangeDecoder.stream?.readByte() ?: 0xFF)
                rangeDecoder.range = rangeDecoder.range shl 8
            }
            1
        }
    }

    companion object {
        const val K_NUM_BIT_MODEL_TOTAL_BITS = 11
        const val K_BIT_MODEL_TOTAL = 1 shl K_NUM_BIT_MODEL_TOTAL_BITS
        const val K_NUM_MOVE_BITS = 5
        const val K_TOP_VALUE = 1 shl 24
    }
}

/** RangeCoderBitTree.cs 的 BitTreeDecoder */
internal class BitTreeDecoder(val numBitLevels: Int) {

    val models: Array<BitDecoder> = Array(1 shl numBitLevels) { BitDecoder() }

    fun init() {
        for (i in 1 until (1 shl numBitLevels)) {
            models[i].init()
        }
    }

    fun decode(rangeDecoder: RangeDecoder): Int {
        var m = 1
        for (bitIndex in numBitLevels downTo 1) {
            m = (m shl 1) + models[m].decode(rangeDecoder)
        }
        return m - (1 shl numBitLevels)
    }

    fun reverseDecode(rangeDecoder: RangeDecoder): Int {
        var m = 1
        var symbol = 0
        for (bitIndex in 0 until numBitLevels) {
            val bit = models[m].decode(rangeDecoder)
            m = (m shl 1) + bit
            symbol = symbol or (bit shl bitIndex)
        }
        return symbol
    }

    companion object {
        fun reverseDecode(
            models: Array<BitDecoder>,
            startIndex: Int,
            rangeDecoder: RangeDecoder,
            numBitLevels: Int
        ): Int {
            var m = 1
            var symbol = 0
            for (bitIndex in 0 until numBitLevels) {
                val bit = models[startIndex + m].decode(rangeDecoder)
                m = (m shl 1) + bit
                symbol = symbol or (bit shl bitIndex)
            }
            return symbol
        }
    }
}

package com.assetstudio.mobile.core.bundle.lzma

/**
 * 来自 7zip/Compress/LZMA/LzmaDecoder.cs（Decoder）、7zip/Compress/LZ/LzOutWindow.cs（OutWindow）、
 * 7zip/ICoder.cs（DataErrorException / InvalidParamException）。
 *
 * 适配内存模型：输入为 ByteArray（经 InBuffer 包装），输出直接写入已知大小的 ByteArray；
 * OutWindow 的环形窗口 flush 时写入输出数组。C# 中未使用的编码器/Train 能力未移植。
 * uint 数值以 Int 保存位模式（回绕运算一致），涉及无符号比较处使用 uintLess/Long 掩码。
 */

/** ICoder.cs: DataErrorException */
internal class DataErrorException : RuntimeException("Data Error")

/** ICoder.cs: InvalidParamException */
internal class InvalidParamException : RuntimeException("Invalid Parameter")

class LzmaDecoder {

    /** LzOutWindow.cs 的 OutWindow，输出流替换为目标 ByteArray */
    private class OutWindow {
        private var buffer: ByteArray = ByteArray(0)
        private var pos: Int = 0
        private var windowSize: Int = 0
        private var streamPos: Int = 0
        private var output: ByteArray = ByteArray(0)
        private var outputPos: Int = 0
        var trainSize: Int = 0
            private set

        fun create(windowSize: Int) {
            if (this.windowSize != windowSize) {
                buffer = ByteArray(windowSize)
            }
            this.windowSize = windowSize
            pos = 0
            streamPos = 0
        }

        fun init(output: ByteArray, outputPos: Int) {
            releaseStream()
            this.output = output
            this.outputPos = outputPos
            streamPos = 0
            pos = 0
            trainSize = 0
        }

        fun releaseStream() {
            flush()
            output = ByteArray(0)
        }

        fun flush() {
            val size = pos - streamPos
            if (size == 0) return
            System.arraycopy(buffer, streamPos, output, outputPos, size)
            outputPos += size
            if (pos >= windowSize) pos = 0
            streamPos = pos
        }

        fun copyBlock(distance: Int, len: Int) {
            // C#: uint pos = _pos - distance - 1; if (pos >= _windowSize) pos += _windowSize;
            var p = pos - distance - 1
            if (p < 0) p += windowSize
            var remaining = len
            while (remaining > 0) {
                if (p >= windowSize) p = 0
                buffer[pos++] = buffer[p++]
                if (pos >= windowSize) flush()
                remaining--
            }
        }

        fun putByte(b: Int) {
            buffer[pos++] = b.toByte()
            if (pos >= windowSize) flush()
        }

        fun getByte(distance: Int): Int {
            var p = pos - distance - 1
            if (p < 0) p += windowSize
            return buffer[p].toInt() and 0xFF
        }
    }

    /** LzmaDecoder.cs 内嵌类 LenDecoder */
    private class LenDecoder {
        private val choice = BitDecoder()
        private val choice2 = BitDecoder()
        private val lowCoder = arrayOfNulls<BitTreeDecoder>(LzmaBase.K_NUM_POS_STATES_MAX)
        private val midCoder = arrayOfNulls<BitTreeDecoder>(LzmaBase.K_NUM_POS_STATES_MAX)
        private val highCoder = BitTreeDecoder(LzmaBase.K_NUM_HIGH_LEN_BITS)
        private var numPosStates = 0

        fun create(numPosStates: Int) {
            for (posState in this.numPosStates until numPosStates) {
                lowCoder[posState] = BitTreeDecoder(LzmaBase.K_NUM_LOW_LEN_BITS)
                midCoder[posState] = BitTreeDecoder(LzmaBase.K_NUM_MID_LEN_BITS)
            }
            this.numPosStates = numPosStates
        }

        fun init() {
            choice.init()
            for (posState in 0 until numPosStates) {
                lowCoder[posState]!!.init()
                midCoder[posState]!!.init()
            }
            choice2.init()
            highCoder.init()
        }

        fun decode(rangeDecoder: RangeDecoder, posState: Int): Int {
            return if (choice.decode(rangeDecoder) == 0) {
                lowCoder[posState]!!.decode(rangeDecoder)
            } else {
                var symbol = LzmaBase.K_NUM_LOW_LEN_SYMBOLS
                if (choice2.decode(rangeDecoder) == 0) {
                    symbol += midCoder[posState]!!.decode(rangeDecoder)
                } else {
                    symbol += LzmaBase.K_NUM_MID_LEN_SYMBOLS
                    symbol += highCoder.decode(rangeDecoder)
                }
                symbol
            }
        }
    }

    /** LzmaDecoder.cs 内嵌类 LiteralDecoder（含 struct Decoder2） */
    private class LiteralDecoder {
        private var coders: Array<Decoder2> = emptyArray()
        private var numPrevBits = 0
        private var numPosBits = 0
        private var posMask = 0

        fun create(numPosBits: Int, numPrevBits: Int) {
            if (coders.isNotEmpty() && this.numPrevBits == numPrevBits && this.numPosBits == numPosBits) {
                return
            }
            this.numPosBits = numPosBits
            this.posMask = (1 shl numPosBits) - 1
            this.numPrevBits = numPrevBits
            val numStates = 1 shl (numPrevBits + numPosBits)
            coders = Array(numStates) { Decoder2() }
        }

        fun init() {
            val numStates = 1 shl (numPrevBits + numPosBits)
            for (i in 0 until numStates) {
                coders[i].init()
            }
        }

        private fun getState(pos: Int, prevByte: Int): Int =
            ((pos and posMask) shl numPrevBits) + (prevByte ushr (8 - numPrevBits))

        fun decodeNormal(rangeDecoder: RangeDecoder, pos: Int, prevByte: Int): Int =
            coders[getState(pos, prevByte)].decodeNormal(rangeDecoder)

        fun decodeWithMatchByte(rangeDecoder: RangeDecoder, pos: Int, prevByte: Int, matchByte: Int): Int =
            coders[getState(pos, prevByte)].decodeWithMatchByte(rangeDecoder, matchByte)

        private class Decoder2 {
            val decoders = Array(0x300) { BitDecoder() }

            fun init() {
                for (i in 0 until 0x300) {
                    decoders[i].init()
                }
            }

            fun decodeNormal(rangeDecoder: RangeDecoder): Int {
                var symbol = 1
                do {
                    symbol = (symbol shl 1) or decoders[symbol].decode(rangeDecoder)
                } while (symbol < 0x100)
                return symbol and 0xFF // (byte)symbol
            }

            fun decodeWithMatchByte(rangeDecoder: RangeDecoder, matchByte: Int): Int {
                var symbol = 1
                var mb = matchByte
                do {
                    val matchBit = (mb ushr 7) and 1
                    mb = (mb shl 1) and 0xFF
                    val bit = decoders[((1 + matchBit) shl 8) + symbol].decode(rangeDecoder)
                    symbol = (symbol shl 1) or bit
                    if (matchBit != bit) {
                        while (symbol < 0x100) {
                            symbol = (symbol shl 1) or decoders[symbol].decode(rangeDecoder)
                        }
                        break
                    }
                } while (symbol < 0x100)
                return symbol and 0xFF
            }
        }
    }

    private val outWindow = OutWindow()
    private val rangeDecoder = RangeDecoder()

    private val isMatchDecoders = Array(LzmaBase.K_NUM_STATES shl LzmaBase.K_NUM_POS_STATES_BITS_MAX) { BitDecoder() }
    private val isRepDecoders = Array(LzmaBase.K_NUM_STATES) { BitDecoder() }
    private val isRepG0Decoders = Array(LzmaBase.K_NUM_STATES) { BitDecoder() }
    private val isRepG1Decoders = Array(LzmaBase.K_NUM_STATES) { BitDecoder() }
    private val isRepG2Decoders = Array(LzmaBase.K_NUM_STATES) { BitDecoder() }
    private val isRep0LongDecoders = Array(LzmaBase.K_NUM_STATES shl LzmaBase.K_NUM_POS_STATES_BITS_MAX) { BitDecoder() }

    private val posSlotDecoder = Array(LzmaBase.K_NUM_LEN_TO_POS_STATES) { BitTreeDecoder(LzmaBase.K_NUM_POS_SLOT_BITS) }
    private val posDecoders = Array(LzmaBase.K_NUM_FULL_DISTANCES - LzmaBase.K_END_POS_MODEL_INDEX) { BitDecoder() }

    private val posAlignDecoder = BitTreeDecoder(LzmaBase.K_NUM_ALIGN_BITS)

    private val lenDecoder = LenDecoder()
    private val repLenDecoder = LenDecoder()

    private val literalDecoder = LiteralDecoder()

    private var dictionarySize = 0xFFFFFFFFL
    private var dictionarySizeCheck = 0L

    private var posStateMask = 0
    private var solid = false

    private fun setDictionarySize(dictionarySize: Long) {
        if (this.dictionarySize != dictionarySize) {
            this.dictionarySize = dictionarySize
            dictionarySizeCheck = maxOf(dictionarySize, 1L)
            val blockSize = maxOf(dictionarySizeCheck, 1L shl 12)
            if (blockSize > Int.MAX_VALUE) {
                throw InvalidParamException()
            }
            outWindow.create(blockSize.toInt())
        }
    }

    private fun setLiteralProperties(lp: Int, lc: Int) {
        if (lp > 8) throw InvalidParamException()
        if (lc > 8) throw InvalidParamException()
        literalDecoder.create(lp, lc)
    }

    private fun setPosBitsProperties(pb: Int) {
        if (pb > LzmaBase.K_NUM_POS_STATES_BITS_MAX) throw InvalidParamException()
        val numPosStates = 1 shl pb
        lenDecoder.create(numPosStates)
        repLenDecoder.create(numPosStates)
        posStateMask = numPosStates - 1
    }

    private fun init(inBuffer: InBuffer, output: ByteArray, outputPos: Int) {
        rangeDecoder.init(inBuffer)
        outWindow.init(output, outputPos)

        for (i in 0 until LzmaBase.K_NUM_STATES) {
            for (j in 0..posStateMask) {
                val index = (i shl LzmaBase.K_NUM_POS_STATES_BITS_MAX) + j
                isMatchDecoders[index].init()
                isRep0LongDecoders[index].init()
            }
            isRepDecoders[i].init()
            isRepG0Decoders[i].init()
            isRepG1Decoders[i].init()
            isRepG2Decoders[i].init()
        }

        literalDecoder.init()
        for (i in 0 until LzmaBase.K_NUM_LEN_TO_POS_STATES) {
            posSlotDecoder[i].init()
        }
        for (i in 0 until LzmaBase.K_NUM_FULL_DISTANCES - LzmaBase.K_END_POS_MODEL_INDEX) {
            posDecoders[i].init()
        }

        lenDecoder.init()
        repLenDecoder.init()
        posAlignDecoder.init()
    }

    /**
     * 对应 C# Decoder.Code(inStream, outStream, inSize, outSize, progress)。
     * inSize/progress 在 C# 实现中也未被使用，此处省略。
     */
    fun code(inBuffer: InBuffer, outData: ByteArray, outPos: Int, outSize: Int) {
        init(inBuffer, outData, outPos)

        val state = LzmaState()
        state.init()
        var rep0 = 0
        var rep1 = 0
        var rep2 = 0
        var rep3 = 0

        var nowPos64 = 0L
        val outSize64 = outSize.toLong()
        if (nowPos64 < outSize64) {
            if (isMatchDecoders[state.index shl LzmaBase.K_NUM_POS_STATES_BITS_MAX].decode(rangeDecoder) != 0) {
                throw DataErrorException()
            }
            state.updateChar()
            val b = literalDecoder.decodeNormal(rangeDecoder, 0, 0)
            outWindow.putByte(b)
            nowPos64++
        }
        while (nowPos64 < outSize64) {
            val posState = nowPos64.toInt() and posStateMask
            if (isMatchDecoders[(state.index shl LzmaBase.K_NUM_POS_STATES_BITS_MAX) + posState].decode(rangeDecoder) == 0) {
                val prevByte = outWindow.getByte(0)
                val b = if (!state.isCharState()) {
                    literalDecoder.decodeWithMatchByte(rangeDecoder, nowPos64.toInt(), prevByte, outWindow.getByte(rep0))
                } else {
                    literalDecoder.decodeNormal(rangeDecoder, nowPos64.toInt(), prevByte)
                }
                outWindow.putByte(b)
                state.updateChar()
                nowPos64++
            } else {
                var len: Int
                if (isRepDecoders[state.index].decode(rangeDecoder) == 1) {
                    if (isRepG0Decoders[state.index].decode(rangeDecoder) == 0) {
                        if (isRep0LongDecoders[(state.index shl LzmaBase.K_NUM_POS_STATES_BITS_MAX) + posState].decode(rangeDecoder) == 0) {
                            state.updateShortRep()
                            outWindow.putByte(outWindow.getByte(rep0))
                            nowPos64++
                            continue
                        }
                    } else {
                        var distance: Int
                        if (isRepG1Decoders[state.index].decode(rangeDecoder) == 0) {
                            distance = rep1
                        } else {
                            if (isRepG2Decoders[state.index].decode(rangeDecoder) == 0) {
                                distance = rep2
                            } else {
                                distance = rep3
                                rep3 = rep2
                            }
                            rep2 = rep1
                        }
                        rep1 = rep0
                        rep0 = distance
                    }
                    len = repLenDecoder.decode(rangeDecoder, posState) + LzmaBase.K_MATCH_MIN_LEN
                    state.updateRep()
                } else {
                    rep3 = rep2
                    rep2 = rep1
                    rep1 = rep0
                    len = LzmaBase.K_MATCH_MIN_LEN + lenDecoder.decode(rangeDecoder, posState)
                    state.updateMatch()
                    val posSlot = posSlotDecoder[LzmaBase.getLenToPosState(len)].decode(rangeDecoder)
                    if (posSlot >= LzmaBase.K_START_POS_MODEL_INDEX) {
                        val numDirectBits = (posSlot ushr 1) - 1
                        rep0 = (2 or (posSlot and 1)) shl numDirectBits
                        if (posSlot < LzmaBase.K_END_POS_MODEL_INDEX) {
                            rep0 += BitTreeDecoder.reverseDecode(posDecoders, rep0 - posSlot - 1, rangeDecoder, numDirectBits)
                        } else {
                            rep0 += rangeDecoder.decodeDirectBits(numDirectBits - LzmaBase.K_NUM_ALIGN_BITS) shl LzmaBase.K_NUM_ALIGN_BITS
                            rep0 += posAlignDecoder.reverseDecode(rangeDecoder)
                        }
                    } else {
                        rep0 = posSlot
                    }
                }
                val rep0Unsigned = rep0.toLong() and 0xFFFFFFFFL
                if (rep0Unsigned >= outWindow.trainSize + nowPos64 || rep0Unsigned >= dictionarySizeCheck) {
                    if (rep0Unsigned == 0xFFFFFFFFL) break
                    throw DataErrorException()
                }
                outWindow.copyBlock(rep0, len)
                nowPos64 += len
            }
        }
        outWindow.flush()
        outWindow.releaseStream()
        rangeDecoder.releaseStream()
    }

    /** 便捷封装：从 ByteArray 的指定偏移开始解码 */
    fun code(inData: ByteArray, inPos: Int, inSize: Int, outData: ByteArray, outPos: Int, outSize: Int) {
        val inBuffer = InBuffer()
        inBuffer.init(inData, inPos)
        code(inBuffer, outData, outPos, outSize)
    }

    /** 对应 C# SetDecoderProperties(byte[]) */
    fun setDecoderProperties(properties: ByteArray) {
        if (properties.size < 5) throw InvalidParamException()
        val lc = (properties[0].toInt() and 0xFF) % 9
        val remainder = (properties[0].toInt() and 0xFF) / 9
        val lp = remainder % 5
        val pb = remainder / 5
        if (pb > LzmaBase.K_NUM_POS_STATES_BITS_MAX) throw InvalidParamException()
        var dictionarySize = 0L
        for (i in 0 until 4) {
            dictionarySize += (properties[1 + i].toLong() and 0xFF) shl (i * 8)
        }
        setDictionarySize(dictionarySize)
        setLiteralProperties(lp, lc)
        setPosBitsProperties(pb)
    }
}

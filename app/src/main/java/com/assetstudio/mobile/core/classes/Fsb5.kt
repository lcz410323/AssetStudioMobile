package com.assetstudio.mobile.core.classes

/*
 * FSB5 音频容器解析器。
 * C# AssetStudio 使用 FMOD 库解码音频，Kotlin 端自行实现 FSB5 头解析与 PCM 提取。
 *
 * 格式参考: FMOD FSB5 二进制格式（HearthSim/python-fsb5、vgmstream fsb5）
 *
 * 布局（全部小端）:
 *  Header(60B): "FSB5" | version(u32) | numSamples | sampleHeaderSize | nameTableSize
 *               | dataSize | mode(u32) | zero[8] | hash[16] | dummy[8]
 *               （version==0 时末尾另有 4 字节 unknown）
 *  SampleHeader(每条 8B + 可选元数据块):
 *    word0: extraParams:1 | frequency:4(索引) | twoChannels:1 | dataOffset:26(单位 16B)
 *    word1: samples:30
 *    元数据块头(u32): next:1 | size:24 | type:7，随后 size 字节数据
 *      type: CHANNELS=1 | FREQUENCY=2 | LOOP=3 | XMASEEK=6 | DSPCOEFF=7 | XWMADATA=10 | VORBISDATA=11
 *  NameTable(若 nameTableSize>0): numSamples 个 u32 偏移 + 各 null 结尾字符串
 *  SampleData: dataStart = 60 + sampleHeaderSize + nameTableSize，sample 的 dataOffset 相对该区
 */

import java.io.ByteArrayOutputStream

class Fsb5(val data: ByteArray) {

    enum class Mode(val value: Int) {
        NONE(0),
        PCM8(1),
        PCM16(2),
        PCM24(3),
        PCM32(4),
        PCMFLOAT(5),
        GCADPCM(6),
        IMAADPCM(7),
        VAG(8),
        HEVAG(9),
        XMA(10),
        MPEG(11),
        CELT(12),
        AT9(13),
        XWMA(14),
        VORBIS(15);

        companion object {
            fun fromValue(value: Int): Mode = entries.firstOrNull { it.value == value } ?: NONE
        }
    }

    enum class ChunkType(val value: Int) {
        CHANNELS(1),
        FREQUENCY(2),
        LOOP(3),
        XMASEEK(6),
        DSPCOEFF(7),
        XWMADATA(10),
        VORBISDATA(11);

        companion object {
            fun fromValue(value: Int): ChunkType? = entries.firstOrNull { it.value == value }
        }
    }

    /** 元数据块 */
    class Chunk(val type: ChunkType, val typeRaw: Int, val size: Int, val offset: Int) {
        fun getData(container: ByteArray): ByteArray =
            if (offset >= 0 && offset + size <= container.size) {
                container.copyOfRange(offset, offset + size)
            } else {
                ByteArray(0)
            }
    }

    class Sample(
        /** 所属 FSB5 的编码格式 */
        val mode: Mode,
        /** 采样率（Hz） */
        val frequency: Int,
        /** 声道数 */
        val channels: Int,
        /** 采样点数 */
        val samples: Long,
        /** 音频数据在 FSB5 中的绝对偏移 */
        val dataOffset: Int,
        /** 元数据块 */
        val metadata: Map<Int, Chunk>,
        /** 名称（无名表时为 null） */
        val name: String?
    ) {
        /** 音频数据切片（由 Fsb5 填充） */
        var data: ByteArray = ByteArray(0)
            internal set

        /** 音频数据长度（字节） */
        val dataLength: Int get() = data.size

        val bitsPerSample: Int
            get() = when (mode) {
                Mode.PCM8 -> 8
                Mode.PCM16 -> 16
                Mode.PCM24 -> 24
                Mode.PCM32, Mode.PCMFLOAT -> 32
                else -> 0
            }

        val isPcm: Boolean
            get() = mode == Mode.PCM8 || mode == Mode.PCM16 || mode == Mode.PCM24 ||
                mode == Mode.PCM32 || mode == Mode.PCMFLOAT

        /** 是否可转 WAV（仅 PCM 族） */
        fun canConvertToWav(): Boolean = isPcm && channels > 0 && frequency > 0

        /**
         * PCM(8/16/24/32bit/float) 转 WAV：44 字节头 + 数据。
         * 非 PCM 格式（Vorbis/AT9/XMA 等）返回 null（需专用解码器）。
         */
        fun toWav(): ByteArray? {
            if (!isPcm || channels <= 0 || frequency <= 0) {
                return null
            }
            val audioData = data
            val isFloat = mode == Mode.PCMFLOAT
            val bits = if (isFloat) 32 else bitsPerSample
            val blockAlign = channels * bits / 8
            val byteRate = frequency * blockAlign

            val out = ByteArrayOutputStream(44 + audioData.size)
            fun u8(v: Int) = out.write(v and 0xFF)
            fun u16(v: Int) {
                u8(v)
                u8(v shr 8)
            }

            fun u32(v: Long) {
                u8(v.toInt())
                u8((v shr 8).toInt())
                u8((v shr 16).toInt())
                u8((v shr 24).toInt())
            }

            // RIFF header
            u8('R'.code); u8('I'.code); u8('F'.code); u8('F'.code)
            u32(36L + audioData.size) // ChunkSize
            u8('W'.code); u8('A'.code); u8('V'.code); u8('E'.code)

            // fmt subchunk
            u8('f'.code); u8('m'.code); u8('t'.code); u8(' '.code)
            u32(16L) // Subchunk1Size (PCM)
            u16(if (isFloat) 3 else 1) // AudioFormat: 1=PCM, 3=IEEE float
            u16(channels)
            u32(frequency.toLong())
            u32(byteRate.toLong())
            u16(blockAlign)
            u16(bits)

            // data subchunk
            u8('d'.code); u8('a'.code); u8('t'.code); u8('a'.code)
            u32(audioData.size.toLong())
            out.write(audioData, 0, audioData.size)

            return out.toByteArray()
        }

        override fun toString(): String =
            "Sample(name=$name, mode=$mode, frequency=$frequency, channels=$channels, " +
                "samples=$samples, dataOffset=$dataOffset, dataLength=$dataLength)"
    }

    /** 内部中间结构：仅记录头字段，数据稍后填充 */
    private class RawSampleHeader(
        val frequency: Int,
        val channels: Int,
        val samples: Long,
        val dataOffset: Int,
        val metadata: Map<Int, Chunk>
    )

    val version: Int
    val numSamples: Int
    val sampleHeaderSize: Int
    val nameTableSize: Int
    val dataSize: Int
    val mode: Mode
    val samples: List<Sample>

    /** 数据区起始偏移 */
    val dataStart: Int

    init {
        if (data.size < 60 || data[0] != 'F'.toByte() || data[1] != 'S'.toByte() ||
            data[2] != 'B'.toByte() || data[3] != '5'.toByte()
        ) {
            throw IllegalArgumentException("Not an FSB5 file")
        }
        var p = 4
        version = readU32(p); p += 4
        numSamples = readU32(p); p += 4
        sampleHeaderSize = readU32(p); p += 4
        nameTableSize = readU32(p); p += 4
        dataSize = readU32(p); p += 4
        mode = Mode.fromValue(readU32(p)); p += 4
        p += 8  // zero
        p += 16 // hash
        p += 8  // dummy
        if (version == 0) {
            p += 4 // unknown
        }
        val headerSize = p

        // ===== sample 表 =====
        val headers = ArrayList<RawSampleHeader>(numSamples)
        var offset = headerSize
        val endOfSamples = minOf(headerSize + sampleHeaderSize, data.size)
        for (i in 0 until numSamples) {
            if (offset + 8 > endOfSamples) break
            val word0 = readU32(offset).toLong() and 0xFFFFFFFFL
            val word1 = readU32(offset + 4).toLong() and 0xFFFFFFFFL

            val extraParams = (word0 and 0x1L).toInt()
            val freqIndex = ((word0 shr 1) and 0xFL).toInt()
            val twoChannels = ((word0 shr 5) and 0x1L).toInt()
            val dataOffset = ((word0 shr 6) and 0x3FFFFFFL).toInt() //26 位，单位 16B
            val sampleCount = word1 and 0x3FFFFFFFL //30 位

            var frequency = frequencyFromIndex(freqIndex)
            var channels = if (twoChannels == 1) 2 else 1
            var pos = offset + 8

            val metadata = LinkedHashMap<Int, Chunk>()
            if (extraParams == 1) {
                var next = 1
                while (next == 1 && pos + 4 <= endOfSamples) {
                    val chunkHeader = readU32(pos).toLong() and 0xFFFFFFFFL
                    next = (chunkHeader and 0x1L).toInt()
                    val size = ((chunkHeader shr 1) and 0xFFFFFFL).toInt()
                    val type = ((chunkHeader shr 25) and 0x7FL).toInt()
                    pos += 4
                    val chunkDataStart = pos
                    when (type) {
                        1 -> { // CHANNELS
                            if (size >= 1 && chunkDataStart < data.size) {
                                channels = data[chunkDataStart].toInt() and 0xFF
                            }
                        }
                        2 -> { // FREQUENCY
                            if (size >= 4 && chunkDataStart + 4 <= data.size) {
                                frequency = readU32(chunkDataStart)
                            }
                        }
                    }
                    metadata[type] = Chunk(ChunkType.fromValue(type) ?: ChunkType.CHANNELS, type, size, chunkDataStart)
                    pos += size
                }
            }
            offset = pos
            headers.add(RawSampleHeader(frequency, channels, sampleCount, dataOffset, metadata))
        }

        // ===== 名字表 =====
        val names = arrayOfNulls<String>(numSamples)
        val nameTableStart = headerSize + sampleHeaderSize
        if (nameTableSize > 0 && nameTableStart + 4 * numSamples <= data.size) {
            val nameOffsets = IntArray(numSamples)
            for (i in 0 until numSamples) {
                nameOffsets[i] = readU32(nameTableStart + i * 4)
            }
            for (i in 0 until numSamples) {
                val strStart = nameTableStart + nameOffsets[i]
                if (strStart in 0 until data.size) {
                    names[i] = readStringToNull(strStart)
                }
            }
        }

        // ===== 数据区：按相邻 sample 的 offset 切片 =====
        dataStart = headerSize + sampleHeaderSize + nameTableSize
        val dataEnd = minOf(dataStart + dataSize, data.size)

        val filled = ArrayList<Sample>(headers.size)
        for (i in headers.indices) {
            val h = headers[i]
            val start = (dataStart + h.dataOffset * 16).coerceIn(0, data.size)
            val end = if (i + 1 < headers.size) {
                (dataStart + headers[i + 1].dataOffset * 16).coerceIn(start, dataEnd)
            } else {
                dataEnd
            }
            val sampleData = data.copyOfRange(start, end)
            filled.add(
                Sample(
                    mode = mode,
                    frequency = h.frequency,
                    channels = h.channels,
                    samples = h.samples,
                    dataOffset = start,
                    metadata = h.metadata,
                    name = names[i]
                ).also { it.data = sampleData }
            )
        }
        samples = filled
    }

    /** 采样率索引 -> Hz（FSB5 频率查找表） */
    private fun frequencyFromIndex(index: Int): Int = when (index) {
        0 -> 4000
        1 -> 8000
        2 -> 11000
        3 -> 11025
        4 -> 16000
        5 -> 22050
        6 -> 24000
        7 -> 32000
        8 -> 44100
        9 -> 48000
        else -> 44100
    }

    private fun readU32(offset: Int): Int {
        if (offset < 0 || offset + 4 > data.size) return 0
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readStringToNull(offset: Int): String {
        val sb = StringBuilder()
        var i = offset
        while (i < data.size && data[i].toInt() != 0) {
            sb.append(data[i].toInt().toChar())
            i++
        }
        return sb.toString()
    }

    companion object {
        /** 尝试解析 FSB5 数据，失败返回 null */
        fun parse(data: ByteArray): Fsb5? {
            return try {
                Fsb5(data)
            } catch (e: Exception) {
                null
            }
        }

        /** 判断字节数据是否为 FSB5 容器 */
        fun isFsb5(data: ByteArray): Boolean =
            data.size > 4 && data[0] == 'F'.toByte() && data[1] == 'S'.toByte() &&
                data[2] == 'B'.toByte() && data[3] == '5'.toByte()
    }
}

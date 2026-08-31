package com.assetstudio.mobile.core.classes

/*
 * 来源: AssetStudio/Classes/AudioClip.cs
 * FSB5 解析在 Fsb5.kt 中自行实现（C# 端使用 FMOD 库解码）。
 */

import com.assetstudio.mobile.core.ObjectReader
import com.assetstudio.mobile.core.ResourceReader
import com.assetstudio.mobile.core.resourceReaderOfData
import com.assetstudio.mobile.core.resourceReaderOfStream

enum class FMODSoundType(val value: Int) {
    UNKNOWN(0),
    ACC(1),
    AIFF(2),
    ASF(3),
    AT3(4),
    CDDA(5),
    DLS(6),
    FLAC(7),
    FSB(8),
    GCADPCM(9),
    IT(10),
    MIDI(11),
    MOD(12),
    MPEG(13),
    OGGVORBIS(14),
    PLAYLIST(15),
    RAW(16),
    S3M(17),
    SF2(18),
    USER(19),
    WAV(20),
    XM(21),
    XMA(22),
    VAG(23),
    AUDIOQUEUE(24),
    XWMA(25),
    BCWAV(26),
    AT9(27),
    VORBIS(28),
    MEDIA_FOUNDATION(29);

    companion object {
        fun fromValue(value: Int): FMODSoundType = entries.firstOrNull { it.value == value } ?: UNKNOWN
    }
}

enum class AudioCompressionFormat(val value: Int) {
    PCM(0),
    Vorbis(1),
    ADPCM(2),
    MP3(3),
    PSMVAG(4),
    HEVAG(5),
    XMA(6),
    AAC(7),
    GCADPCM(8),
    ATRAC9(9);

    companion object {
        fun fromValue(value: Int): AudioCompressionFormat =
            entries.firstOrNull { it.value == value } ?: PCM
    }
}

class AudioClip(reader: ObjectReader) : NamedObject(reader) {
    var m_Format: Int = 0
    var m_Type: FMODSoundType = FMODSoundType.UNKNOWN
    var m_3D: Boolean = false
    var m_UseHardware: Boolean = false

    //version 5
    var m_LoadType: Int = 0
    var m_Channels: Int = 0
    var m_Frequency: Int = 0
    var m_BitsPerSample: Int = 0
    var m_Length: Float = 0f
    var m_IsTrackerFormat: Boolean = false
    var m_SubsoundIndex: Int = 0
    var m_PreloadAudioData: Boolean = false
    var m_LoadInBackground: Boolean = false
    var m_Legacy3D: Boolean = false
    var m_CompressionFormat: AudioCompressionFormat = AudioCompressionFormat.PCM

    var m_Source: String? = null
    var m_Offset: Long = 0 //ulong
    var m_Size: Long = 0 //ulong
    lateinit var m_AudioData: ResourceReader
        private set

    /** 惰性解析的 FSB5 容器（数据为 FSB5 时可用） */
    val fsb5: Fsb5? by lazy {
        val data = try {
            m_AudioData.getData()
        } catch (e: Exception) {
            return@lazy null
        }
        if (Fsb5.isFsb5(data)) Fsb5.parse(data) else null
    }

    init {
        if (version[0] < 5) {
            m_Format = reader.readInt32()
            m_Type = FMODSoundType.fromValue(reader.readInt32())
            m_3D = reader.readBoolean()
            m_UseHardware = reader.readBoolean()
            reader.alignStream()

            if (version[0] >= 4 || (version[0] == 3 && version[1] >= 2)) { //3.2.0 to 5
                val m_Stream = reader.readInt32()
                m_Size = reader.readInt32().toLong()
                val tsize = if (m_Size % 4 != 0L) m_Size + 4 - m_Size % 4 else m_Size
                if (reader.byteSize + reader.byteStart - reader.position != tsize) {
                    m_Offset = reader.readUInt32().toLong()
                    m_Source = assetsFile.fullName + ".resS"
                }
            } else {
                m_Size = reader.readInt32().toLong()
            }
        } else {
            m_LoadType = reader.readInt32()
            m_Channels = reader.readInt32()
            m_Frequency = reader.readInt32()
            m_BitsPerSample = reader.readInt32()
            m_Length = reader.readSingle()
            m_IsTrackerFormat = reader.readBoolean()
            reader.alignStream()
            m_SubsoundIndex = reader.readInt32()
            m_PreloadAudioData = reader.readBoolean()
            m_LoadInBackground = reader.readBoolean()
            m_Legacy3D = reader.readBoolean()
            reader.alignStream()

            //StreamedResource m_Resource
            m_Source = reader.readAlignedString()
            m_Offset = reader.readInt64()
            m_Size = reader.readInt64()
            m_CompressionFormat = AudioCompressionFormat.fromValue(reader.readInt32())
        }

        val source = m_Source
        m_AudioData = if (!source.isNullOrEmpty()) {
            resourceReaderOfStream(assetsFile, source, m_Offset, m_Size)
        } else {
            // 内联数据：记录绝对偏移，惰性读取，避免 reader 后续复用导致位置漂移
            resourceReaderOfData(reader.fileBuffer, reader.position, m_Size.toInt())
        }
    }

    /** 取出音频数据（FSB5 容器或原始数据） */
    fun getAudioData(): ByteArray = m_AudioData.getData()

    /** 若为 FSB5 容器，尝试将指定 sample 转为 WAV（默认第 0 个） */
    fun toWav(sampleIndex: Int = 0): ByteArray? {
        val fsb = fsb5 ?: return null
        if (sampleIndex < 0 || sampleIndex >= fsb.samples.size) return null
        return fsb.samples[sampleIndex].toWav()
    }

    /** 若为 FSB5 容器，列出全部 sample 信息（格式/频率/通道/偏移） */
    fun listSamples(): List<Fsb5.Sample> = fsb5?.samples ?: emptyList()
}

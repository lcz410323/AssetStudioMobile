package com.assetstudio.mobile.core.classes

/*
 * 来源: AssetStudio/Classes/VideoClip.cs（StreamedResource / VideoClip）
 */

import com.assetstudio.mobile.core.ObjectReader
import com.assetstudio.mobile.core.ResourceReader
import com.assetstudio.mobile.core.resourceReaderOfData
import com.assetstudio.mobile.core.resourceReaderOfStream

class StreamedResource(reader: ObjectReader) {
    val m_Source: String = reader.readAlignedString()
    val m_Offset: Long = reader.readInt64() //ulong
    val m_Size: Long = reader.readInt64() //ulong
}

class VideoClip(reader: ObjectReader) : NamedObject(reader) {
    lateinit var m_VideoData: ResourceReader
        private set
    val m_OriginalPath: String
    val m_ExternalResources: StreamedResource
    /** 视频宽高（C# 中为局部变量，此处保留为字段便于展示） */
    val m_Width: Int
    val m_Height: Int

    init {
        m_OriginalPath = reader.readAlignedString()
        val m_ProxyWidth = reader.readUInt32()
        val m_ProxyHeight = reader.readUInt32()
        m_Width = reader.readUInt32().toInt()
        m_Height = reader.readUInt32().toInt()
        if (version[0] > 2017 || (version[0] == 2017 && version[1] >= 2)) { //2017.2 and up
            val m_PixelAspecRatioNum = reader.readUInt32()
            val m_PixelAspecRatioDen = reader.readUInt32()
        }
        val m_FrameRate = reader.readDouble()
        val m_FrameCount = reader.readInt64() //ReadUInt64
        val m_Format = reader.readInt32()
        val m_AudioChannelCount = reader.readUInt16Array()
        reader.alignStream()
        val m_AudioSampleRate = reader.readUInt32Array()
        val m_AudioLanguage = reader.readStringArray()
        if (version[0] >= 2020) { //2020.1 and up
            val m_VideoShadersSize = reader.readInt32()
            for (i in 0 until m_VideoShadersSize) {
                val m_VideoShader = PPtr(reader)
            }
        }
        m_ExternalResources = StreamedResource(reader)
        val m_HasSplitAlpha = reader.readBoolean()
        if (version[0] >= 2020) { //2020.1 and up
            val m_sRGB = reader.readBoolean()
        }

        m_VideoData = if (m_ExternalResources.m_Source.isNotEmpty()) {
            resourceReaderOfStream(assetsFile, m_ExternalResources.m_Source, m_ExternalResources.m_Offset, m_ExternalResources.m_Size)
        } else {
            // 内联数据：记录绝对偏移，惰性读取
            resourceReaderOfData(reader.fileBuffer, reader.position, m_ExternalResources.m_Size.toInt())
        }
    }

    /** 取出视频数据 */
    fun getVideoData(): ByteArray = m_VideoData.getData()
}

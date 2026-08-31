package com.assetstudio.mobile.core.classes

/*
 * 来源: AssetStudio/Classes/Texture2D.cs（StreamingInfo / GLTextureSettings / Texture2D）
 * TextureFormat 枚举沿用 TextureFormat.kt，不重复定义。
 * imageData 采用惰性 ResourceReader：
 *  - 内联数据: 捕获 (fileBuffer, absoluteOffset, size)，与 reader 后续位置无关；
 *  - 流数据:   延迟从 assetsManager.resourceFileReaders 解析 .resS 后截取。
 */

import com.assetstudio.mobile.core.ObjectReader
import com.assetstudio.mobile.core.ResourceReader
import com.assetstudio.mobile.core.resourceReaderOfData
import com.assetstudio.mobile.core.resourceReaderOfStream

class StreamingInfo(reader: ObjectReader) {
    val offset: Long //ulong
    val size: Long //uint
    val path: String

    init {
        if (reader.version[0] >= 2020) { //2020.1 and up
            offset = reader.readInt64()
        } else {
            offset = reader.readUInt32()
        }
        size = reader.readUInt32()
        path = reader.readAlignedString()
    }
}

class GLTextureSettings(reader: ObjectReader) {
    val m_FilterMode: Int
    val m_Aniso: Int
    val m_MipBias: Float
    val m_WrapMode: Int

    init {
        m_FilterMode = reader.readInt32()
        m_Aniso = reader.readInt32()
        m_MipBias = reader.readSingle()
        if (reader.version[0] >= 2017) { //2017.x and up
            m_WrapMode = reader.readInt32() //m_WrapU
            val m_WrapV = reader.readInt32()
            val m_WrapW = reader.readInt32()
        } else {
            m_WrapMode = reader.readInt32()
        }
    }
}

class Texture2D(reader: ObjectReader) : Texture(reader) {
    val m_Width: Int
    val m_Height: Int
    val m_TextureFormatRaw: Int
    val m_TextureFormat: TextureFormat
    var m_MipMap: Boolean = false
    var m_MipCount: Int = 0
    val m_TextureSettings: GLTextureSettings
    val m_CompleteImageSize: Int
    val m_ImageCount: Int
    val m_TextureDimension: Int
    val imageData: ResourceReader
    var m_StreamData: StreamingInfo? = null

    init {
        m_Width = reader.readInt32()
        m_Height = reader.readInt32()
        m_CompleteImageSize = reader.readInt32()
        if (version[0] >= 2020) { //2020.1 and up
            val m_MipsStripped = reader.readInt32()
        }
        m_TextureFormatRaw = reader.readInt32()
        m_TextureFormat = TextureFormat.fromValue(m_TextureFormatRaw) ?: TextureFormat.RGBA32
        if (version[0] < 5 || (version[0] == 5 && version[1] < 2)) { //5.2 down
            m_MipMap = reader.readBoolean()
        } else {
            m_MipCount = reader.readInt32()
        }
        if (version[0] > 2 || (version[0] == 2 && version[1] >= 6)) { //2.6.0 and up
            val m_IsReadable = reader.readBoolean()
        }
        if (version[0] >= 2020) { //2020.1 and up
            val m_IsPreProcessed = reader.readBoolean()
        }
        if (version[0] > 2019 || (version[0] == 2019 && version[1] >= 3)) { //2019.3 and up
            val m_IgnoreMasterTextureLimit = reader.readBoolean()
        }
        if (version[0] >= 3) { //3.0.0 - 5.4
            if (version[0] < 5 || (version[0] == 5 && version[1] <= 4)) {
                val m_ReadAllowed = reader.readBoolean()
            }
        }
        if (version[0] > 2018 || (version[0] == 2018 && version[1] >= 2)) { //2018.2 and up
            val m_StreamingMipmaps = reader.readBoolean()
        }
        reader.alignStream()
        if (version[0] > 2018 || (version[0] == 2018 && version[1] >= 2)) { //2018.2 and up
            val m_StreamingMipmapsPriority = reader.readInt32()
        }
        m_ImageCount = reader.readInt32()
        m_TextureDimension = reader.readInt32()
        m_TextureSettings = GLTextureSettings(reader)
        if (version[0] >= 3) { //3.0 and up
            val m_LightmapFormat = reader.readInt32()
        }
        if (version[0] > 3 || (version[0] == 3 && version[1] >= 5)) { //3.5.0 and up
            val m_ColorSpace = reader.readInt32()
        }
        if (version[0] > 2020 || (version[0] == 2020 && version[1] >= 2)) { //2020.2 and up
            val m_PlatformBlob = reader.readUInt8Array()
            reader.alignStream()
        }
        val image_data_size = reader.readInt32()
        if (image_data_size == 0 && ((version[0] == 5 && version[1] >= 3) || version[0] > 5)) { //5.3.0 and up
            m_StreamData = StreamingInfo(reader)
        }

        val streamData = m_StreamData
        if (streamData != null && streamData.path.isNotEmpty()) {
            imageData = resourceReaderOfStream(assetsFile, streamData.path, streamData.offset, streamData.size)
        } else {
            // 内联数据：记录绝对偏移，惰性读取，避免 reader 后续复用导致位置漂移
            val dataOffset = reader.position
            imageData = resourceReaderOfData(reader.fileBuffer, dataOffset, image_data_size)
        }
    }

    /** 一次性取出全部像素数据（内联或流式） */
    fun getImageData(): ByteArray = imageData.getData()
}

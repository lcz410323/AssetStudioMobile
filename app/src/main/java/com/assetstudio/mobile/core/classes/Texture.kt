package com.assetstudio.mobile.core.classes

/*
 * 来源: AssetStudio/Classes/Texture.cs
 * 注意：TextureFormat 枚举已在 TextureFormat.kt 中定义，此处不重复。
 */

import com.assetstudio.mobile.core.ObjectReader

abstract class Texture(reader: ObjectReader) : NamedObject(reader) {

    init {
        if (version[0] > 2017 || (version[0] == 2017 && version[1] >= 3)) { //2017.3 and up
            val m_ForcedFallbackFormat = reader.readInt32()
            val m_DownscaleFallback = reader.readBoolean()
            if (version[0] > 2020 || (version[0] == 2020 && version[1] >= 2)) { //2020.2 and up
                val m_IsAlphaChannelOptional = reader.readBoolean()
            }
            reader.alignStream()
        }
    }
}

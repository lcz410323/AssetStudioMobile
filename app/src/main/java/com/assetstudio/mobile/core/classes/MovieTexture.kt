package com.assetstudio.mobile.core.classes

/*
 * 来源: AssetStudio/Classes/MovieTexture.cs
 */

import com.assetstudio.mobile.core.ObjectReader

class MovieTexture(reader: ObjectReader) : Texture(reader) {
    val m_MovieData: ByteArray
    val m_AudioClip: PPtr

    init {
        val m_Loop = reader.readBoolean()
        reader.alignStream()
        m_AudioClip = PPtr(reader)
        m_MovieData = reader.readUInt8Array()
    }
}

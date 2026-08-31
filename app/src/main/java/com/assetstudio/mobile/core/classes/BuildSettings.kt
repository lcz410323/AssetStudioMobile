package com.assetstudio.mobile.core.classes

/*
 * 来源: AssetStudio/Classes/BuildSettings.cs
 */

import com.assetstudio.mobile.core.ObjectReader

class BuildSettings(reader: ObjectReader) : UnityObject(reader) {
    val m_Version: String

    init {
        val levels = reader.readStringArray()

        val hasRenderTexture = reader.readBoolean()
        val hasPROVersion = reader.readBoolean()
        val hasPublishingRights = reader.readBoolean()
        val hasShadows = reader.readBoolean()

        m_Version = reader.readAlignedString()
    }
}

package com.assetstudio.mobile.core.classes

/*
 * 来源: AssetStudio/Classes/Font.cs
 * characterInfo/kerning 等未使用字段按 C# 原样跳过。
 */

import com.assetstudio.mobile.core.ObjectReader

class Font(reader: ObjectReader) : NamedObject(reader) {
    var m_FontData: ByteArray? = null

    init {
        if ((version[0] == 5 && version[1] >= 5) || version[0] > 5) { //5.5 and up
            val m_LineSpacing = reader.readSingle()
            val m_DefaultMaterial = PPtr(reader)
            val m_FontSize = reader.readSingle()
            val m_Texture = PPtr(reader)
            val m_AsciiStartOffset = reader.readInt32()
            val m_Tracking = reader.readSingle()
            val m_CharacterSpacing = reader.readInt32()
            val m_CharacterPadding = reader.readInt32()
            val m_ConvertCase = reader.readInt32()
            val m_CharacterRects_size = reader.readInt32()
            for (i in 0 until m_CharacterRects_size) {
                reader.position += 44 //CharacterInfo data 41
            }
            val m_KerningValues_size = reader.readInt32()
            for (i in 0 until m_KerningValues_size) {
                reader.position += 8
            }
            val m_PixelScale = reader.readSingle()
            val m_FontData_size = reader.readInt32()
            if (m_FontData_size > 0) {
                m_FontData = reader.readBytes(m_FontData_size)
            }
        } else {
            val m_AsciiStartOffset = reader.readInt32()

            if (version[0] <= 3) {
                val m_FontCountX = reader.readInt32()
                val m_FontCountY = reader.readInt32()
            }

            val m_Kerning = reader.readSingle()
            val m_LineSpacing = reader.readSingle()

            if (version[0] <= 3) {
                val m_PerCharacterKerning_size = reader.readInt32()
                for (i in 0 until m_PerCharacterKerning_size) {
                    val first = reader.readInt32()
                    val second = reader.readSingle()
                }
            } else {
                val m_CharacterSpacing = reader.readInt32()
                val m_CharacterPadding = reader.readInt32()
            }

            val m_ConvertCase = reader.readInt32()
            val m_DefaultMaterial = PPtr(reader)

            val m_CharacterRects_size = reader.readInt32()
            for (i in 0 until m_CharacterRects_size) {
                val index = reader.readInt32()
                //Rectf uv
                val uvx = reader.readSingle()
                val uvy = reader.readSingle()
                val uvwidth = reader.readSingle()
                val uvheight = reader.readSingle()
                //Rectf vert
                val vertx = reader.readSingle()
                val verty = reader.readSingle()
                val vertwidth = reader.readSingle()
                val vertheight = reader.readSingle()
                val width = reader.readSingle()

                if (version[0] >= 4) {
                    val flipped = reader.readBoolean()
                    reader.alignStream()
                }
            }

            val m_Texture = PPtr(reader)

            val m_KerningValues_size = reader.readInt32()
            for (i in 0 until m_KerningValues_size) {
                val pairfirst = reader.readInt16()
                val pairsecond = reader.readInt16()
                val second = reader.readSingle()
            }

            if (version[0] <= 3) {
                val m_GridFont = reader.readBoolean()
                reader.alignStream()
            } else {
                val m_PixelScale = reader.readSingle()
            }

            val m_FontData_size = reader.readInt32()
            if (m_FontData_size > 0) {
                m_FontData = reader.readBytes(m_FontData_size)
            }
        }
    }
}

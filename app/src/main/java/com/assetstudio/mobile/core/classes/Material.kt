package com.assetstudio.mobile.core.classes

/*
 * 来源: AssetStudio/Classes/Material.cs（UnityTexEnv / UnityPropertySheet / Material）
 */

import com.assetstudio.mobile.core.ObjectReader
import com.assetstudio.mobile.core.math.Color
import com.assetstudio.mobile.core.math.Vector2

class UnityTexEnv(reader: ObjectReader) {
    val m_Texture: PPtr = PPtr(reader) // PPtr<Texture>
    val m_Scale: Vector2 = reader.readVector2()
    val m_Offset: Vector2 = reader.readVector2()
}

class UnityPropertySheet(reader: ObjectReader) {
    val m_TexEnvs: Array<Pair<String, UnityTexEnv>>
    val m_Ints: Array<Pair<String, Int>>?
    val m_Floats: Array<Pair<String, Float>>
    val m_Colors: Array<Pair<String, Color>>

    init {
        val version = reader.version

        val m_TexEnvsSize = reader.readArrayCount()
        m_TexEnvs = Array(m_TexEnvsSize) {
            reader.readAlignedString() to UnityTexEnv(reader)
        }

        if (version[0] >= 2021) { //2021.1 and up
            val m_IntsSize = reader.readArrayCount()
            m_Ints = Array(m_IntsSize) {
                reader.readAlignedString() to reader.readInt32()
            }
        } else {
            m_Ints = null
        }

        val m_FloatsSize = reader.readArrayCount()
        m_Floats = Array(m_FloatsSize) {
            reader.readAlignedString() to reader.readSingle()
        }

        val m_ColorsSize = reader.readArrayCount()
        m_Colors = Array(m_ColorsSize) {
            reader.readAlignedString() to reader.readColor4()
        }
    }
}

class Material(reader: ObjectReader) : NamedObject(reader) {
    val m_Shader: PPtr // PPtr<Shader>
    val m_SavedProperties: UnityPropertySheet

    init {
        m_Shader = PPtr(reader)

        if (version[0] == 4 && version[1] >= 1) { //4.x
            val m_ShaderKeywords = reader.readStringArray()
        }

        if (version[0] > 2021 || (version[0] == 2021 && version[1] >= 3)) { //2021.3 and up
            val m_ValidKeywords = reader.readStringArray()
            val m_InvalidKeywords = reader.readStringArray()
        } else if (version[0] >= 5) { //5.0 ~ 2021.2
            val m_ShaderKeywords = reader.readAlignedString()
        }

        if (version[0] >= 5) { //5.0 and up
            val m_LightmapFlags = reader.readUInt32()
        }

        if (version[0] > 5 || (version[0] == 5 && version[1] >= 6)) { //5.6 and up
            val m_EnableInstancingVariants = reader.readBoolean()
            //var m_DoubleSidedGI = reader.readBoolean(); //2017 and up
            reader.alignStream()
        }

        if (version[0] > 4 || (version[0] == 4 && version[1] >= 3)) { //4.3 and up
            val m_CustomRenderQueue = reader.readInt32()
        }

        if (version[0] > 5 || (version[0] == 5 && version[1] >= 1)) { //5.1 and up
            val stringTagMapSize = reader.readInt32()
            for (i in 0 until stringTagMapSize) {
                val first = reader.readAlignedString()
                val second = reader.readAlignedString()
            }
        }

        if (version[0] > 5 || (version[0] == 5 && version[1] >= 6)) { //5.6 and up
            val disabledShaderPasses = reader.readStringArray()
        }

        m_SavedProperties = UnityPropertySheet(reader)

        //vector m_BuildTextureStacks 2020 and up
    }
}

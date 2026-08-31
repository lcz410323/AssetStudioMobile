package com.assetstudio.mobile.core.classes

/*
 * 来源: AssetStudio/Classes/SkinnedMeshRenderer.cs
 */

import com.assetstudio.mobile.core.ObjectReader

class SkinnedMeshRenderer(reader: ObjectReader) : Renderer(reader) {
    val m_Mesh: PPtr // PPtr<Mesh>
    val m_Bones: Array<PPtr> // PPtr<Transform>[]
    val m_BlendShapeWeights: FloatArray?

    init {
        val m_Quality = reader.readInt32()
        val m_UpdateWhenOffscreen = reader.readBoolean()
        val m_SkinNormals = reader.readBoolean() //3.1.0 and below
        reader.alignStream()

        if (version[0] == 2 && version[1] < 6) { //2.6 down
            val m_DisableAnimationWhenOffscreen = PPtr(reader) // PPtr<Animation>
        }

        m_Mesh = PPtr(reader)

        val m_BonesCount = reader.readArrayCount()
        m_Bones = Array(m_BonesCount) { PPtr(reader) }

        if (version[0] > 4 || (version[0] == 4 && version[1] >= 3)) { //4.3 and up
            m_BlendShapeWeights = reader.readSingleArray()
        } else {
            m_BlendShapeWeights = null
        }
    }
}

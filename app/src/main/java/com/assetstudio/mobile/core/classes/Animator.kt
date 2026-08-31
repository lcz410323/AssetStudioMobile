package com.assetstudio.mobile.core.classes

/*
 * 来源: AssetStudio/Classes/Animator.cs
 */

import com.assetstudio.mobile.core.ObjectReader

class Animator(reader: ObjectReader) : Behaviour(reader) {
    val m_Avatar: PPtr
    val m_Controller: PPtr
    var m_HasTransformHierarchy: Boolean = true

    init {
        m_Avatar = PPtr(reader)
        m_Controller = PPtr(reader)
        val m_CullingMode = reader.readInt32()

        if (version[0] > 4 || (version[0] == 4 && version[1] >= 5)) { //4.5 and up
            val m_UpdateMode = reader.readInt32()
        }

        val m_ApplyRootMotion = reader.readBoolean()
        if (version[0] == 4 && version[1] >= 5) { //4.5 and up - 5.0 down
            reader.alignStream()
        }

        if (version[0] >= 5) { //5.0 and up
            val m_LinearVelocityBlending = reader.readBoolean()
            if (version[0] > 2021 || (version[0] == 2021 && version[1] >= 2)) { //2021.2 and up
                val m_StabilizeFeet = reader.readBoolean()
            }
            reader.alignStream()
        }

        if (version[0] < 4 || (version[0] == 4 && version[1] < 5)) { //4.5 down
            val m_AnimatePhysics = reader.readBoolean()
        }

        if (version[0] > 4 || (version[0] == 4 && version[1] >= 3)) { //4.3 and up
            m_HasTransformHierarchy = reader.readBoolean()
        }

        if (version[0] > 4 || (version[0] == 4 && version[1] >= 5)) { //4.5 and up
            val m_AllowConstantClipSamplingOptimization = reader.readBoolean()
        }
        if (version[0] >= 5 && version[0] < 2018) { //5.0 and up - 2018 down
            reader.alignStream()
        }

        if (version[0] >= 2018) { //2018 and up
            val m_KeepAnimatorControllerStateOnDisable = reader.readBoolean()
            reader.alignStream()
        }
    }
}

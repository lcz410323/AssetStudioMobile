package com.assetstudio.mobile.core.classes

/*
 * 来源: AssetStudio/Classes/Renderer.cs
 */

import com.assetstudio.mobile.core.ObjectReader

abstract class Renderer(reader: ObjectReader) : Component(reader) {
    val m_Materials: Array<PPtr> // PPtr<Material>[]
    var m_StaticBatchInfo: StaticBatchInfo? = null
    var m_SubsetIndices: LongArray? = null

    init {
        if (version[0] < 5) { //5.0 down
            val m_Enabled = reader.readBoolean()
            val m_CastShadows = reader.readBoolean()
            val m_ReceiveShadows = reader.readBoolean()
            val m_LightmapIndex = reader.readUInt8()
        } else { //5.0 and up
            if (version[0] > 5 || (version[0] == 5 && version[1] >= 4)) { //5.4 and up
                val m_Enabled = reader.readBoolean()
                val m_CastShadows = reader.readUInt8()
                val m_ReceiveShadows = reader.readUInt8()
                if (version[0] > 2017 || (version[0] == 2017 && version[1] >= 2)) { //2017.2 and up
                    val m_DynamicOccludee = reader.readUInt8()
                }
                if (version[0] >= 2021) { //2021.1 and up
                    val m_StaticShadowCaster = reader.readUInt8()
                }
                val m_MotionVectors = reader.readUInt8()
                val m_LightProbeUsage = reader.readUInt8()
                val m_ReflectionProbeUsage = reader.readUInt8()
                if (version[0] > 2019 || (version[0] == 2019 && version[1] >= 3)) { //2019.3 and up
                    val m_RayTracingMode = reader.readUInt8()
                }
                if (version[0] >= 2020) { //2020.1 and up
                    val m_RayTraceProcedural = reader.readUInt8()
                }
                reader.alignStream()
            } else {
                val m_Enabled = reader.readBoolean()
                reader.alignStream()
                val m_CastShadows = reader.readUInt8()
                val m_ReceiveShadows = reader.readBoolean()
                reader.alignStream()
            }

            if (version[0] >= 2018) { //2018 and up
                val m_RenderingLayerMask = reader.readUInt32()
            }

            if (version[0] > 2018 || (version[0] == 2018 && version[1] >= 3)) { //2018.3 and up
                val m_RendererPriority = reader.readInt32()
            }

            val m_LightmapIndex = reader.readUInt16()
            val m_LightmapIndexDynamic = reader.readUInt16()
        }

        if (version[0] >= 3) { //3.0 and up
            val m_LightmapTilingOffset = reader.readVector4()
        }

        if (version[0] >= 5) { //5.0 and up
            val m_LightmapTilingOffsetDynamic = reader.readVector4()
        }

        val m_MaterialsSize = reader.readArrayCount()
        m_Materials = Array(m_MaterialsSize) { PPtr(reader) }

        if (version[0] < 3) { //3.0 down
            val m_LightmapTilingOffset = reader.readVector4()
        } else { //3.0 and up
            if (version[0] > 5 || (version[0] == 5 && version[1] >= 5)) { //5.5 and up
                m_StaticBatchInfo = StaticBatchInfo(reader)
            } else {
                m_SubsetIndices = reader.readUInt32Array()
            }

            val m_StaticBatchRoot = PPtr(reader) // PPtr<Transform>
        }

        if (version[0] > 5 || (version[0] == 5 && version[1] >= 4)) { //5.4 and up
            val m_ProbeAnchor = PPtr(reader) // PPtr<Transform>
            val m_LightProbeVolumeOverride = PPtr(reader) // PPtr<GameObject>
        } else if (version[0] > 3 || (version[0] == 3 && version[1] >= 5)) { //3.5 - 5.3
            val m_UseLightProbes = reader.readBoolean()
            reader.alignStream()

            if (version[0] >= 5) { //5.0 and up
                val m_ReflectionProbeUsage = reader.readInt32()
            }

            val m_LightProbeAnchor = PPtr(reader) //5.0 and up m_ProbeAnchor, PPtr<Transform>
        }

        if (version[0] > 4 || (version[0] == 4 && version[1] >= 3)) { //4.3 and up
            if (version[0] == 4 && version[1] == 3) { //4.3
                val m_SortingLayer = reader.readInt16()
            } else {
                val m_SortingLayerID = reader.readUInt32()
            }

            //SInt16 m_SortingLayer 5.6 and up
            val m_SortingOrder = reader.readInt16()
            reader.alignStream()
        }
    }
}

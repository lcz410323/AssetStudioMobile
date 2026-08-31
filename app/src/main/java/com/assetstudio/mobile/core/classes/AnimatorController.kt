package com.assetstudio.mobile.core.classes

/*
 * 来源: AssetStudio/Classes/AnimatorController.cs
 * ValueArrayConstant 定义于 AnimationClip.kt（与 C# 一致）
 */

import com.assetstudio.mobile.core.ObjectReader
import com.assetstudio.mobile.core.math.Vector2
import com.assetstudio.mobile.core.math.Vector3
import com.assetstudio.mobile.core.math.Vector4

class HumanPoseMask(reader: ObjectReader) {
    val word0: Long = reader.readUInt32()
    val word1: Long = reader.readUInt32()
    var word2: Long = 0

    init {
        val version = reader.version
        if (version[0] > 5 || (version[0] == 5 && version[1] >= 2)) { //5.2 and up
            word2 = reader.readUInt32()
        }
    }
}

class SkeletonMaskElement(reader: ObjectReader) {
    val m_PathHash: Long = reader.readUInt32()
    val m_Weight: Float = reader.readSingle()
}

class SkeletonMask(reader: ObjectReader) {
    val m_Data: Array<SkeletonMaskElement> = Array(reader.readArrayCount()) { SkeletonMaskElement(reader) }
}

class LayerConstant(reader: ObjectReader) {
    val m_StateMachineIndex: Long //uint
    val m_StateMachineMotionSetIndex: Long //uint
    val m_BodyMask: HumanPoseMask
    val m_SkeletonMask: SkeletonMask
    val m_Binding: Long //uint
    val m_LayerBlendingMode: Int
    var m_DefaultWeight: Float = 0f
    val m_IKPass: Boolean
    var m_SyncedLayerAffectsTiming: Boolean = false

    init {
        val version = reader.version

        m_StateMachineIndex = reader.readUInt32()
        m_StateMachineMotionSetIndex = reader.readUInt32()
        m_BodyMask = HumanPoseMask(reader)
        m_SkeletonMask = SkeletonMask(reader)
        m_Binding = reader.readUInt32()
        m_LayerBlendingMode = reader.readInt32()
        if (version[0] > 4 || (version[0] == 4 && version[1] >= 2)) { //4.2 and up
            m_DefaultWeight = reader.readSingle()
        }
        m_IKPass = reader.readBoolean()
        if (version[0] > 4 || (version[0] == 4 && version[1] >= 2)) { //4.2 and up
            m_SyncedLayerAffectsTiming = reader.readBoolean()
        }
        reader.alignStream()
    }
}

class ConditionConstant(reader: ObjectReader) {
    val m_ConditionMode: Long = reader.readUInt32()
    val m_EventID: Long = reader.readUInt32()
    val m_EventThreshold: Float = reader.readSingle()
    val m_ExitTime: Float = reader.readSingle()
}

class TransitionConstant(reader: ObjectReader) {
    val m_ConditionConstantArray: Array<ConditionConstant>
    val m_DestinationState: Long //uint
    var m_FullPathID: Long = 0 //uint
    val m_ID: Long //uint
    val m_UserID: Long //uint
    val m_TransitionDuration: Float
    val m_TransitionOffset: Float
    var m_ExitTime: Float = 0f
    var m_HasExitTime: Boolean = false
    var m_HasFixedDuration: Boolean = false
    var m_InterruptionSource: Int = 0
    var m_OrderedInterruption: Boolean = false
    var m_Atomic: Boolean = false
    var m_CanTransitionToSelf: Boolean = false

    init {
        val version = reader.version

        m_ConditionConstantArray = Array(reader.readArrayCount()) { ConditionConstant(reader) }

        m_DestinationState = reader.readUInt32()
        if (version[0] >= 5) { //5.0 and up
            m_FullPathID = reader.readUInt32()
        }

        m_ID = reader.readUInt32()
        m_UserID = reader.readUInt32()
        m_TransitionDuration = reader.readSingle()
        m_TransitionOffset = reader.readSingle()
        if (version[0] >= 5) { //5.0 and up
            m_ExitTime = reader.readSingle()
            m_HasExitTime = reader.readBoolean()
            m_HasFixedDuration = reader.readBoolean()
            reader.alignStream()
            m_InterruptionSource = reader.readInt32()
            m_OrderedInterruption = reader.readBoolean()
        } else {
            m_Atomic = reader.readBoolean()
        }

        if (version[0] > 4 || (version[0] == 4 && version[1] >= 5)) { //4.5 and up
            m_CanTransitionToSelf = reader.readBoolean()
        }

        reader.alignStream()
    }
}

class LeafInfoConstant(reader: ObjectReader) {
    val m_IDArray: LongArray = reader.readUInt32Array()
    val m_IndexOffset: Long = reader.readUInt32()
}

class MotionNeighborList(reader: ObjectReader) {
    val m_NeighborArray: LongArray = reader.readUInt32Array()
}

class Blend2dDataConstant(reader: ObjectReader) {
    val m_ChildPositionArray: Array<Vector2> = reader.readVector2Array()
    val m_ChildMagnitudeArray: FloatArray = reader.readSingleArray()
    val m_ChildPairVectorArray: Array<Vector2> = reader.readVector2Array()
    val m_ChildPairAvgMagInvArray: FloatArray = reader.readSingleArray()
    val m_ChildNeighborListArray: Array<MotionNeighborList>

    init {
        m_ChildNeighborListArray = Array(reader.readArrayCount()) { MotionNeighborList(reader) }
    }
}

class Blend1dDataConstant(reader: ObjectReader) { // wrong labeled
    val m_ChildThresholdArray: FloatArray = reader.readSingleArray()
}

class BlendDirectDataConstant(reader: ObjectReader) {
    val m_ChildBlendEventIDArray: LongArray = reader.readUInt32Array()
    val m_NormalizedBlendValues: Boolean = reader.readBoolean()

    init {
        reader.alignStream()
    }
}

class BlendTreeNodeConstant(reader: ObjectReader) {
    var m_BlendType: Long = 0 //uint
    val m_BlendEventID: Long //uint
    var m_BlendEventYID: Long = 0 //uint
    val m_ChildIndices: LongArray //uint[]
    var m_ChildThresholdArray: FloatArray? = null
    var m_Blend1dData: Blend1dDataConstant? = null
    var m_Blend2dData: Blend2dDataConstant? = null
    var m_BlendDirectData: BlendDirectDataConstant? = null
    val m_ClipID: Long //uint
    var m_ClipIndex: Long = 0 //uint
    val m_Duration: Float
    var m_CycleOffset: Float = 0f
    var m_Mirror: Boolean = false

    init {
        val version = reader.version

        if (version[0] > 4 || (version[0] == 4 && version[1] >= 1)) { //4.1 and up
            m_BlendType = reader.readUInt32()
        }
        m_BlendEventID = reader.readUInt32()
        if (version[0] > 4 || (version[0] == 4 && version[1] >= 1)) { //4.1 and up
            m_BlendEventYID = reader.readUInt32()
        }
        m_ChildIndices = reader.readUInt32Array()
        if (version[0] < 4 || (version[0] == 4 && version[1] < 1)) { //4.1 down
            m_ChildThresholdArray = reader.readSingleArray()
        }

        if (version[0] > 4 || (version[0] == 4 && version[1] >= 1)) { //4.1 and up
            m_Blend1dData = Blend1dDataConstant(reader)
            m_Blend2dData = Blend2dDataConstant(reader)
        }

        if (version[0] >= 5) { //5.0 and up
            m_BlendDirectData = BlendDirectDataConstant(reader)
        }

        m_ClipID = reader.readUInt32()
        if (version[0] == 4 && version[1] >= 5) { //4.5 - 5.0
            m_ClipIndex = reader.readUInt32()
        }

        m_Duration = reader.readSingle()

        if (version[0] > 4 ||
            (version[0] == 4 && version[1] > 1) ||
            (version[0] == 4 && version[1] == 1 && version[2] >= 3)
        ) { //4.1.3 and up
            m_CycleOffset = reader.readSingle()
            m_Mirror = reader.readBoolean()
            reader.alignStream()
        }
    }
}

class BlendTreeConstant(reader: ObjectReader) {
    val m_NodeArray: Array<BlendTreeNodeConstant>
    var m_BlendEventArrayConstant: ValueArrayConstant? = null

    init {
        val version = reader.version

        m_NodeArray = Array(reader.readArrayCount()) { BlendTreeNodeConstant(reader) }

        if (version[0] < 4 || (version[0] == 4 && version[1] < 5)) { //4.5 down
            m_BlendEventArrayConstant = ValueArrayConstant(reader)
        }
    }
}

class StateConstant(reader: ObjectReader) {
    val m_TransitionConstantArray: Array<TransitionConstant>
    val m_BlendTreeConstantIndexArray: IntArray
    var m_LeafInfoArray: Array<LeafInfoConstant>? = null
    val m_BlendTreeConstantArray: Array<BlendTreeConstant>
    val m_NameID: Long //uint
    var m_PathID: Long = 0 //uint
    var m_FullPathID: Long = 0 //uint
    val m_TagID: Long //uint
    var m_SpeedParamID: Long = 0 //uint
    var m_MirrorParamID: Long = 0 //uint
    var m_CycleOffsetParamID: Long = 0 //uint
    val m_Speed: Float
    var m_CycleOffset: Float = 0f
    val m_IKOnFeet: Boolean
    var m_WriteDefaultValues: Boolean = false
    val m_Loop: Boolean
    var m_Mirror: Boolean = false

    init {
        val version = reader.version

        m_TransitionConstantArray = Array(reader.readArrayCount()) { TransitionConstant(reader) }

        m_BlendTreeConstantIndexArray = reader.readInt32Array()

        if (version[0] < 5 || (version[0] == 5 && version[1] < 2)) { //5.2 down
            m_LeafInfoArray = Array(reader.readArrayCount()) { LeafInfoConstant(reader) }
        }

        m_BlendTreeConstantArray = Array(reader.readArrayCount()) { BlendTreeConstant(reader) }

        m_NameID = reader.readUInt32()
        if (version[0] > 4 || (version[0] == 4 && version[1] >= 3)) { //4.3 and up
            m_PathID = reader.readUInt32()
        }
        if (version[0] >= 5) { //5.0 and up
            m_FullPathID = reader.readUInt32()
        }

        m_TagID = reader.readUInt32()
        if (version[0] > 5 || (version[0] == 5 && version[1] >= 1)) { //5.1 and up
            m_SpeedParamID = reader.readUInt32()
            m_MirrorParamID = reader.readUInt32()
            m_CycleOffsetParamID = reader.readUInt32()
        }

        if (version[0] > 2017 || (version[0] == 2017 && version[1] >= 2)) { //2017.2 and up
            val m_TimeParamID = reader.readUInt32()
        }

        m_Speed = reader.readSingle()
        if (version[0] > 4 || (version[0] == 4 && version[1] >= 1)) { //4.1 and up
            m_CycleOffset = reader.readSingle()
        }
        m_IKOnFeet = reader.readBoolean()
        if (version[0] >= 5) { //5.0 and up
            m_WriteDefaultValues = reader.readBoolean()
        }

        m_Loop = reader.readBoolean()
        if (version[0] > 4 || (version[0] == 4 && version[1] >= 1)) { //4.1 and up
            m_Mirror = reader.readBoolean()
        }

        reader.alignStream()
    }
}

class SelectorTransitionConstant(reader: ObjectReader) {
    val m_Destination: Long = reader.readUInt32()
    val m_ConditionConstantArray: Array<ConditionConstant> =
        Array(reader.readArrayCount()) { ConditionConstant(reader) }
}

class SelectorStateConstant(reader: ObjectReader) {
    val m_TransitionConstantArray: Array<SelectorTransitionConstant>
    val m_FullPathID: Long //uint
    val m_isEntry: Boolean

    init {
        m_TransitionConstantArray = Array(reader.readArrayCount()) { SelectorTransitionConstant(reader) }

        m_FullPathID = reader.readUInt32()
        m_isEntry = reader.readBoolean()
        reader.alignStream()
    }
}

class StateMachineConstant(reader: ObjectReader) {
    val m_StateConstantArray: Array<StateConstant>
    val m_AnyStateTransitionConstantArray: Array<TransitionConstant>
    var m_SelectorStateConstantArray: Array<SelectorStateConstant>? = null
    val m_DefaultState: Long //uint
    val m_MotionSetCount: Long //uint

    init {
        val version = reader.version

        m_StateConstantArray = Array(reader.readArrayCount()) { StateConstant(reader) }

        m_AnyStateTransitionConstantArray = Array(reader.readArrayCount()) { TransitionConstant(reader) }

        if (version[0] >= 5) { //5.0 and up
            m_SelectorStateConstantArray = Array(reader.readArrayCount()) { SelectorStateConstant(reader) }
        }

        m_DefaultState = reader.readUInt32()
        m_MotionSetCount = reader.readUInt32()
    }
}

class ValueArray(reader: ObjectReader) {
    var m_BoolValues: BooleanArray? = null
    var m_IntValues: IntArray? = null
    var m_FloatValues: FloatArray? = null
    var m_VectorValues: Array<Vector4>? = null
    var m_PositionValues: Array<Vector3>? = null
    var m_QuaternionValues: Array<Vector4>? = null
    var m_ScaleValues: Array<Vector3>? = null

    init {
        val version = reader.version

        if (version[0] < 5 || (version[0] == 5 && version[1] < 5)) { //5.5 down
            m_BoolValues = reader.readBooleanArray()
            reader.alignStream()
            m_IntValues = reader.readInt32Array()
            m_FloatValues = reader.readSingleArray()
        }

        if (version[0] < 4 || (version[0] == 4 && version[1] < 3)) { //4.3 down
            m_VectorValues = reader.readVector4Array()
        } else {
            val numPosValues = reader.readArrayCount()
            m_PositionValues = Array(numPosValues) { reader.readVector3Compat() }

            m_QuaternionValues = reader.readVector4Array()

            val numScaleValues = reader.readArrayCount()
            m_ScaleValues = Array(numScaleValues) { reader.readVector3Compat() }

            if (version[0] > 5 || (version[0] == 5 && version[1] >= 5)) { //5.5 and up
                m_FloatValues = reader.readSingleArray()
                m_IntValues = reader.readInt32Array()
                m_BoolValues = reader.readBooleanArray()
                reader.alignStream()
            }
        }
    }
}

class ControllerConstant(reader: ObjectReader) {
    val m_LayerArray: Array<LayerConstant>
    val m_StateMachineArray: Array<StateMachineConstant>
    val m_Values: ValueArrayConstant
    val m_DefaultValues: ValueArray

    init {
        m_LayerArray = Array(reader.readArrayCount()) { LayerConstant(reader) }

        m_StateMachineArray = Array(reader.readArrayCount()) { StateMachineConstant(reader) }

        m_Values = ValueArrayConstant(reader)
        m_DefaultValues = ValueArray(reader)
    }
}

class AnimatorController(reader: ObjectReader) : RuntimeAnimatorController(reader) {
    val m_AnimationClips: Array<PPtr>
    val m_TOS: Array<Pair<Long, String>>

    init {
        val m_ControllerSize = reader.readUInt32()
        val m_Controller = ControllerConstant(reader)

        val tosSize = reader.readArrayCount()
        m_TOS = Array(tosSize) { Pair(reader.readUInt32(), reader.readAlignedString()) }

        m_AnimationClips = Array(reader.readArrayCount()) { PPtr(reader) }
    }
}

package com.assetstudio.mobile.core.classes

/*
 * 来源: AssetStudio/Classes/Avatar.cs
 */

import com.assetstudio.mobile.core.ObjectReader
import com.assetstudio.mobile.core.math.Vector3
import com.assetstudio.mobile.core.math.Vector4

class Node(reader: ObjectReader) {
    val m_ParentId: Int = reader.readInt32()
    val m_AxesId: Int = reader.readInt32()
}

class Limit(reader: ObjectReader) {
    val m_Min: Any
    val m_Max: Any

    init {
        val version = reader.version
        if (version[0] > 5 || (version[0] == 5 && version[1] >= 4)) { //5.4 and up
            m_Min = reader.readVector3()
            m_Max = reader.readVector3()
        } else {
            m_Min = reader.readVector4()
            m_Max = reader.readVector4()
        }
    }
}

class Axes(reader: ObjectReader) {
    val m_PreQ: Vector4
    val m_PostQ: Vector4
    val m_Sgn: Any
    val m_Limit: Limit
    val m_Length: Float
    val m_Type: Long //uint

    init {
        val version = reader.version
        m_PreQ = reader.readVector4()
        m_PostQ = reader.readVector4()
        m_Sgn = if (version[0] > 5 || (version[0] == 5 && version[1] >= 4)) { //5.4 and up
            reader.readVector3()
        } else {
            reader.readVector4()
        }
        m_Limit = Limit(reader)
        m_Length = reader.readSingle()
        m_Type = reader.readUInt32()
    }
}

class Skeleton(reader: ObjectReader) {
    val m_Node: Array<Node>
    val m_ID: LongArray
    val m_AxesArray: Array<Axes>

    init {
        m_Node = Array(reader.readArrayCount()) { Node(reader) }

        m_ID = reader.readUInt32Array()

        m_AxesArray = Array(reader.readArrayCount()) { Axes(reader) }
    }
}

class SkeletonPose(reader: ObjectReader) {
    val m_X: Array<com.assetstudio.mobile.core.math.XForm> =
        Array(reader.readArrayCount()) { reader.readXForm() }
}

class Hand(reader: ObjectReader) {
    val m_HandBoneIndex: IntArray = reader.readInt32Array()
}

class Handle(reader: ObjectReader) {
    val m_X: com.assetstudio.mobile.core.math.XForm = reader.readXForm()
    val m_ParentHumanIndex: Long = reader.readUInt32()
    val m_ID: Long = reader.readUInt32()
}

class Collider(reader: ObjectReader) {
    val m_X: com.assetstudio.mobile.core.math.XForm = reader.readXForm()
    val m_Type: Long = reader.readUInt32()
    val m_XMotionType: Long = reader.readUInt32()
    val m_YMotionType: Long = reader.readUInt32()
    val m_ZMotionType: Long = reader.readUInt32()
    val m_MinLimitX: Float = reader.readSingle()
    val m_MaxLimitX: Float = reader.readSingle()
    val m_MaxLimitY: Float = reader.readSingle()
    val m_MaxLimitZ: Float = reader.readSingle()
}

class Human(reader: ObjectReader) {
    val m_RootX: com.assetstudio.mobile.core.math.XForm
    val m_Skeleton: Skeleton
    val m_SkeletonPose: SkeletonPose
    val m_LeftHand: Hand
    val m_RightHand: Hand
    var m_Handles: Array<Handle>? = null
    var m_ColliderArray: Array<Collider>? = null
    val m_HumanBoneIndex: IntArray
    val m_HumanBoneMass: FloatArray
    var m_ColliderIndex: IntArray? = null
    val m_Scale: Float
    val m_ArmTwist: Float
    val m_ForeArmTwist: Float
    val m_UpperLegTwist: Float
    val m_LegTwist: Float
    val m_ArmStretch: Float
    val m_LegStretch: Float
    val m_FeetSpacing: Float
    val m_HasLeftHand: Boolean
    val m_HasRightHand: Boolean
    var m_HasTDoF: Boolean = false

    init {
        val version = reader.version
        m_RootX = reader.readXForm()
        m_Skeleton = Skeleton(reader)
        m_SkeletonPose = SkeletonPose(reader)
        m_LeftHand = Hand(reader)
        m_RightHand = Hand(reader)

        if (version[0] < 2018 || (version[0] == 2018 && version[1] < 2)) { //2018.2 down
            m_Handles = Array(reader.readArrayCount()) { Handle(reader) }
            m_ColliderArray = Array(reader.readArrayCount()) { Collider(reader) }
        }

        m_HumanBoneIndex = reader.readInt32Array()

        m_HumanBoneMass = reader.readSingleArray()

        if (version[0] < 2018 || (version[0] == 2018 && version[1] < 2)) { //2018.2 down
            m_ColliderIndex = reader.readInt32Array()
        }

        m_Scale = reader.readSingle()
        m_ArmTwist = reader.readSingle()
        m_ForeArmTwist = reader.readSingle()
        m_UpperLegTwist = reader.readSingle()
        m_LegTwist = reader.readSingle()
        m_ArmStretch = reader.readSingle()
        m_LegStretch = reader.readSingle()
        m_FeetSpacing = reader.readSingle()
        m_HasLeftHand = reader.readBoolean()
        m_HasRightHand = reader.readBoolean()
        if (version[0] > 5 || (version[0] == 5 && version[1] >= 2)) { //5.2 and up
            m_HasTDoF = reader.readBoolean()
        }
        reader.alignStream()
    }
}

class AvatarConstant(reader: ObjectReader) {
    val m_AvatarSkeleton: Skeleton
    val m_AvatarSkeletonPose: SkeletonPose
    var m_DefaultPose: SkeletonPose? = null
    var m_SkeletonNameIDArray: LongArray? = null
    val m_Human: Human
    val m_HumanSkeletonIndexArray: IntArray
    var m_HumanSkeletonReverseIndexArray: IntArray? = null
    val m_RootMotionBoneIndex: Int
    val m_RootMotionBoneX: com.assetstudio.mobile.core.math.XForm
    var m_RootMotionSkeleton: Skeleton? = null
    var m_RootMotionSkeletonPose: SkeletonPose? = null
    var m_RootMotionSkeletonIndexArray: IntArray? = null

    init {
        val version = reader.version
        m_AvatarSkeleton = Skeleton(reader)
        m_AvatarSkeletonPose = SkeletonPose(reader)

        if (version[0] > 4 || (version[0] == 4 && version[1] >= 3)) { //4.3 and up
            m_DefaultPose = SkeletonPose(reader)
            m_SkeletonNameIDArray = reader.readUInt32Array()
        }

        m_Human = Human(reader)

        m_HumanSkeletonIndexArray = reader.readInt32Array()

        if (version[0] > 4 || (version[0] == 4 && version[1] >= 3)) { //4.3 and up
            m_HumanSkeletonReverseIndexArray = reader.readInt32Array()
        }

        m_RootMotionBoneIndex = reader.readInt32()
        m_RootMotionBoneX = reader.readXForm()

        if (version[0] > 4 || (version[0] == 4 && version[1] >= 3)) { //4.3 and up
            m_RootMotionSkeleton = Skeleton(reader)
            m_RootMotionSkeletonPose = SkeletonPose(reader)
            m_RootMotionSkeletonIndexArray = reader.readInt32Array()
        }
    }
}

class Avatar(reader: ObjectReader) : NamedObject(reader) {
    val m_AvatarSize: Long //uint
    val m_Avatar: AvatarConstant
    val m_TOS: Array<Pair<Long, String>>

    init {
        m_AvatarSize = reader.readUInt32()
        m_Avatar = AvatarConstant(reader)

        val numTOS = reader.readArrayCount()
        m_TOS = Array(numTOS) { Pair(reader.readUInt32(), reader.readAlignedString()) }

        //HumanDescription m_HumanDescription 2019 and up
    }

    fun findBonePath(hash: Long): String? = m_TOS.firstOrNull { it.first == hash }?.second
}

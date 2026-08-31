package com.assetstudio.mobile.core.classes

/*
 * 来源: AssetStudio/Classes/AnimationClip.cs
 * PackedFloatVector / PackedIntVector / PackedQuatVector 已移至 PackedVectors.kt
 * AABB / xform 的读取辅助见 ReaderExtensions.kt
 */

import com.assetstudio.mobile.core.ClassIDType
import com.assetstudio.mobile.core.ObjectReader
import com.assetstudio.mobile.core.math.Quaternion
import com.assetstudio.mobile.core.math.Vector3
import com.assetstudio.mobile.core.math.Vector4

class Keyframe<T>(reader: ObjectReader, readerFunc: (ObjectReader) -> T) {
    val time: Float = reader.readSingle()
    val value: T = readerFunc(reader)
    val inSlope: T = readerFunc(reader)
    val outSlope: T = readerFunc(reader)
    var weightedMode: Int = 0
    var inWeight: T? = null
    var outWeight: T? = null

    init {
        if (reader.version[0] >= 2018) { //2018 and up
            weightedMode = reader.readInt32()
            inWeight = readerFunc(reader)
            outWeight = readerFunc(reader)
        }
    }
}

class AnimationCurve<T>(reader: ObjectReader, readerFunc: (ObjectReader) -> T) {
    val m_Curve: Array<Keyframe<T>>
    val m_PreInfinity: Int
    val m_PostInfinity: Int
    var m_RotationOrder: Int = 0

    init {
        val version = reader.version
        m_Curve = Array(reader.readArrayCount()) { Keyframe(reader, readerFunc) }

        m_PreInfinity = reader.readInt32()
        m_PostInfinity = reader.readInt32()
        if (version[0] > 5 || (version[0] == 5 && version[1] >= 3)) { //5.3 and up
            m_RotationOrder = reader.readInt32()
        }
    }
}

class QuaternionCurve(reader: ObjectReader) {
    val curve: AnimationCurve<Quaternion> = AnimationCurve(reader) { it.readQuaternion() }
    val path: String = reader.readAlignedString()
}

class CompressedAnimationCurve(reader: ObjectReader) {
    val m_Path: String = reader.readAlignedString()
    val m_Times: PackedIntVector = PackedIntVector(reader)
    val m_Values: PackedQuatVector = PackedQuatVector(reader)
    val m_Slopes: PackedFloatVector = PackedFloatVector(reader)
    val m_PreInfinity: Int = reader.readInt32()
    val m_PostInfinity: Int = reader.readInt32()
}

class Vector3Curve(reader: ObjectReader) {
    val curve: AnimationCurve<Vector3> = AnimationCurve(reader) { it.readVector3() }
    val path: String = reader.readAlignedString()
}

class FloatCurve(reader: ObjectReader) {
    val curve: AnimationCurve<Float> = AnimationCurve(reader) { it.readSingle() }
    val attribute: String = reader.readAlignedString()
    val path: String = reader.readAlignedString()
    val classID: ClassIDType = ClassIDType.fromValue(reader.readInt32())
    val script: PPtr = PPtr(reader)
}

class PPtrKeyframe(reader: ObjectReader) {
    val time: Float = reader.readSingle()
    val value: PPtr = PPtr(reader)
}

class PPtrCurve(reader: ObjectReader) {
    val curve: Array<PPtrKeyframe> = Array(reader.readArrayCount()) { PPtrKeyframe(reader) }
    val attribute: String = reader.readAlignedString()
    val path: String = reader.readAlignedString()
    val classID: Int = reader.readInt32()
    val script: PPtr = PPtr(reader)
}

class HandPose(reader: ObjectReader) {
    val m_GrabX = reader.readXForm()
    val m_DoFArray: FloatArray = reader.readSingleArray()
    val m_Override: Float = reader.readSingle()
    val m_CloseOpen: Float = reader.readSingle()
    val m_InOut: Float = reader.readSingle()
    val m_Grab: Float = reader.readSingle()
}

class HumanGoal(reader: ObjectReader) {
    val m_X = reader.readXForm()
    val m_WeightT: Float = reader.readSingle()
    val m_WeightR: Float = reader.readSingle()
    var m_HintT: Vector3? = null
    var m_HintWeightT: Float = 0f

    init {
        val version = reader.version
        if (version[0] >= 5) { //5.0 and up
            m_HintT = reader.readVector3Compat()
            m_HintWeightT = reader.readSingle()
        }
    }
}

class HumanPose(reader: ObjectReader) {
    val m_RootX = reader.readXForm()
    val m_LookAtPosition: Vector3 = reader.readVector3Compat()
    val m_LookAtWeight: Vector4 = reader.readVector4()
    val m_GoalArray: Array<HumanGoal>
    val m_LeftHandPose: HandPose
    val m_RightHandPose: HandPose
    val m_DoFArray: FloatArray
    var m_TDoFArray: Array<Vector3>? = null

    init {
        val version = reader.version
        m_GoalArray = Array(reader.readArrayCount()) { HumanGoal(reader) }

        m_LeftHandPose = HandPose(reader)
        m_RightHandPose = HandPose(reader)

        m_DoFArray = reader.readSingleArray()

        if (version[0] > 5 || (version[0] == 5 && version[1] >= 2)) { //5.2 and up
            val numTDof = reader.readArrayCount()
            m_TDoFArray = Array(numTDof) { reader.readVector3Compat() }
        }
    }
}

class StreamedClip(reader: ObjectReader) {
    val data: LongArray = reader.readUInt32Array()
    val curveCount: Long = reader.readUInt32()

    class StreamedCurveKey(reader: com.assetstudio.mobile.core.io.EndianBinaryReader) {
        val index: Int = reader.readInt32()
        val coeff: FloatArray = reader.readSingleArray(4)

        val value: Float get() = coeff[3]
        val outSlope: Float get() = coeff[2]
        var inSlope: Float = 0f

        fun calculateNextInSlope(dxParam: Float, rhs: StreamedCurveKey): Float {
            //Stepped
            if (coeff[0] == 0f && coeff[1] == 0f && coeff[2] == 0f) {
                return Float.POSITIVE_INFINITY
            }

            val dx = maxOf(dxParam, 0.0001f)
            val dy = rhs.value - value
            val length = 1.0f / (dx * dx)
            val d1 = outSlope * dx
            val d2 = dy + dy + dy - d1 - d1 - coeff[1] / length
            return d2 / dx
        }
    }

    class StreamedFrame(reader: com.assetstudio.mobile.core.io.EndianBinaryReader) {
        val time: Float = reader.readSingle()
        val keyList: Array<StreamedCurveKey> = Array(reader.readArrayCount()) { StreamedCurveKey(reader) }
    }

    fun readData(): List<StreamedFrame> {
        val frameList = ArrayList<StreamedFrame>()
        // uint32 数组转小端字节流后顺序读取（C# BinaryReader 为小端）
        val buffer = ByteArray(data.size * 4)
        var p = 0
        for (v in data) {
            val x = v.toInt()
            buffer[p++] = (x and 0xFF).toByte()
            buffer[p++] = ((x ushr 8) and 0xFF).toByte()
            buffer[p++] = ((x ushr 16) and 0xFF).toByte()
            buffer[p++] = ((x ushr 24) and 0xFF).toByte()
        }
        val frameReader = com.assetstudio.mobile.core.io.EndianBinaryReader(
            buffer,
            com.assetstudio.mobile.core.io.EndianType.LittleEndian
        )
        while (frameReader.position < frameReader.length) {
            frameList.add(StreamedFrame(frameReader))
        }

        for (frameIndex in 2 until frameList.size - 1) {
            val frame = frameList[frameIndex]
            for (curveKey in frame.keyList) {
                for (i in frameIndex - 1 downTo 0) {
                    val preFrame = frameList[i]
                    val preCurveKey = preFrame.keyList.firstOrNull { it.index == curveKey.index }
                    if (preCurveKey != null) {
                        curveKey.inSlope =
                            preCurveKey.calculateNextInSlope(frame.time - preFrame.time, curveKey)
                        break
                    }
                }
            }
        }
        return frameList
    }
}

class DenseClip(reader: ObjectReader) {
    val m_FrameCount: Int = reader.readInt32()
    val m_CurveCount: Long = reader.readUInt32()
    val m_SampleRate: Float = reader.readSingle()
    val m_BeginTime: Float = reader.readSingle()
    val m_SampleArray: FloatArray = reader.readSingleArray()
}

class ConstantClip(reader: ObjectReader) {
    val data: FloatArray = reader.readSingleArray()
}

class ValueConstant(reader: ObjectReader) {
    val m_ID: Long //uint
    var m_TypeID: Long = 0 //uint
    val m_Type: Long //uint
    val m_Index: Long //uint

    init {
        val version = reader.version
        m_ID = reader.readUInt32()
        if (version[0] < 5 || (version[0] == 5 && version[1] < 5)) { //5.5 down
            m_TypeID = reader.readUInt32()
        }
        m_Type = reader.readUInt32()
        m_Index = reader.readUInt32()
    }
}

class ValueArrayConstant(reader: ObjectReader) {
    val m_ValueArray: Array<ValueConstant> = Array(reader.readArrayCount()) { ValueConstant(reader) }
}

class Clip(reader: ObjectReader) {
    val m_StreamedClip: StreamedClip
    val m_DenseClip: DenseClip
    var m_ConstantClip: ConstantClip? = null
    var m_Binding: ValueArrayConstant? = null

    init {
        val version = reader.version
        m_StreamedClip = StreamedClip(reader)
        m_DenseClip = DenseClip(reader)
        if (version[0] > 4 || (version[0] == 4 && version[1] >= 3)) { //4.3 and up
            m_ConstantClip = ConstantClip(reader)
        }
        if (version[0] < 2018 || (version[0] == 2018 && version[1] < 3)) { //2018.3 down
            m_Binding = ValueArrayConstant(reader)
        }
    }

    fun convertValueArrayToGenericBinding(): AnimationClipBindingConstant {
        val genericBindings = ArrayList<GenericBinding>()
        val values = m_Binding
        var i = 0
        if (values != null) {
            while (i < values.m_ValueArray.size) {
                val curveID = values.m_ValueArray[i].m_ID
                val curveTypeID = values.m_ValueArray[i].m_TypeID
                val binding = GenericBinding()
                genericBindings.add(binding)
                if (curveTypeID == 4174552735L) { //CRC(PositionX)
                    binding.path = curveID
                    binding.attribute = 1 //kBindTransformPosition
                    binding.typeID = ClassIDType.Transform
                    i += 3
                } else if (curveTypeID == 2211994246L) { //CRC(QuaternionX)
                    binding.path = curveID
                    binding.attribute = 2 //kBindTransformRotation
                    binding.typeID = ClassIDType.Transform
                    i += 4
                } else if (curveTypeID == 1512518241L) { //CRC(ScaleX)
                    binding.path = curveID
                    binding.attribute = 3 //kBindTransformScale
                    binding.typeID = ClassIDType.Transform
                    i += 3
                } else {
                    binding.typeID = ClassIDType.Animator
                    binding.path = 0
                    binding.attribute = curveID
                    i++
                }
            }
        }
        val bindings = AnimationClipBindingConstant()
        bindings.genericBindings = genericBindings.toTypedArray()
        return bindings
    }
}

class ValueDelta(reader: ObjectReader) {
    val m_Start: Float = reader.readSingle()
    val m_Stop: Float = reader.readSingle()
}

class ClipMuscleConstant(reader: ObjectReader) {
    val m_DeltaPose: HumanPose
    val m_StartX = reader.readXForm()
    var m_StopX: com.assetstudio.mobile.core.math.XForm? = null
    val m_LeftFootStartX = reader.readXForm()
    val m_RightFootStartX = reader.readXForm()
    var m_MotionStartX: com.assetstudio.mobile.core.math.XForm? = null
    var m_MotionStopX: com.assetstudio.mobile.core.math.XForm? = null
    val m_AverageSpeed: Vector3
    val m_Clip: Clip
    val m_StartTime: Float
    val m_StopTime: Float
    val m_OrientationOffsetY: Float
    val m_Level: Float
    val m_CycleOffset: Float
    val m_AverageAngularSpeed: Float
    val m_IndexArray: IntArray
    val m_ValueArrayDelta: Array<ValueDelta>
    var m_ValueArrayReferencePose: FloatArray? = null
    var m_Mirror: Boolean = false
    var m_LoopTime: Boolean = false
    var m_LoopBlend: Boolean = false
    var m_LoopBlendOrientation: Boolean = false
    var m_LoopBlendPositionY: Boolean = false
    var m_LoopBlendPositionXZ: Boolean = false
    var m_StartAtOrigin: Boolean = false
    var m_KeepOriginalOrientation: Boolean = false
    var m_KeepOriginalPositionY: Boolean = false
    var m_KeepOriginalPositionXZ: Boolean = false
    var m_HeightFromFeet: Boolean = false

    init {
        val version = reader.version
        m_DeltaPose = HumanPose(reader)
        if (version[0] > 5 || (version[0] == 5 && version[1] >= 5)) { //5.5 and up
            m_StopX = reader.readXForm()
        }
        if (version[0] < 5) { //5.0 down
            m_MotionStartX = reader.readXForm()
            m_MotionStopX = reader.readXForm()
        }
        m_AverageSpeed = reader.readVector3Compat()
        m_Clip = Clip(reader)
        m_StartTime = reader.readSingle()
        m_StopTime = reader.readSingle()
        m_OrientationOffsetY = reader.readSingle()
        m_Level = reader.readSingle()
        m_CycleOffset = reader.readSingle()
        m_AverageAngularSpeed = reader.readSingle()

        m_IndexArray = reader.readInt32Array()
        if (version[0] < 4 || (version[0] == 4 && version[1] < 3)) { //4.3 down
            val m_AdditionalCurveIndexArray = reader.readInt32Array()
        }
        m_ValueArrayDelta = Array(reader.readArrayCount()) { ValueDelta(reader) }
        if (version[0] > 5 || (version[0] == 5 && version[1] >= 3)) { //5.3 and up
            m_ValueArrayReferencePose = reader.readSingleArray()
        }

        m_Mirror = reader.readBoolean()
        if (version[0] > 4 || (version[0] == 4 && version[1] >= 3)) { //4.3 and up
            m_LoopTime = reader.readBoolean()
        }
        m_LoopBlend = reader.readBoolean()
        m_LoopBlendOrientation = reader.readBoolean()
        m_LoopBlendPositionY = reader.readBoolean()
        m_LoopBlendPositionXZ = reader.readBoolean()
        if (version[0] > 5 || (version[0] == 5 && version[1] >= 5)) { //5.5 and up
            m_StartAtOrigin = reader.readBoolean()
        }
        m_KeepOriginalOrientation = reader.readBoolean()
        m_KeepOriginalPositionY = reader.readBoolean()
        m_KeepOriginalPositionXZ = reader.readBoolean()
        m_HeightFromFeet = reader.readBoolean()
        reader.alignStream()
    }
}

class GenericBinding {
    var path: Long = 0 //uint
    var attribute: Long = 0 //uint
    var script: PPtr? = null
    var typeID: ClassIDType = ClassIDType.Object
    var customType: Int = 0 //byte
    var isPPtrCurve: Int = 0 //byte
    var isIntCurve: Int = 0 //byte

    constructor()

    constructor(reader: ObjectReader) {
        val version = reader.version
        path = reader.readUInt32()
        attribute = reader.readUInt32()
        script = PPtr(reader)
        typeID = if (version[0] > 5 || (version[0] == 5 && version[1] >= 6)) { //5.6 and up
            ClassIDType.fromValue(reader.readInt32())
        } else {
            ClassIDType.fromValue(reader.readUInt16())
        }
        customType = reader.readUInt8()
        isPPtrCurve = reader.readUInt8()
        if (version[0] > 2022 || (version[0] == 2022 && version[1] >= 1)) { //2022.1 and up
            isIntCurve = reader.readUInt8()
        }
        reader.alignStream()
    }
}

class AnimationClipBindingConstant {
    var genericBindings: Array<GenericBinding> = emptyArray()
    var pptrCurveMapping: Array<PPtr> = emptyArray()

    constructor()

    constructor(reader: ObjectReader) {
        genericBindings = Array(reader.readArrayCount()) { GenericBinding(reader) }
        pptrCurveMapping = Array(reader.readArrayCount()) { PPtr(reader) }
    }

    fun findBinding(index: Int): GenericBinding? {
        var curves = 0
        for (b in genericBindings) {
            if (b.typeID == ClassIDType.Transform) {
                when (b.attribute.toInt()) {
                    1, 3, 4 -> curves += 3 //kBindTransformPosition / Scale / Euler
                    2 -> curves += 4 //kBindTransformRotation
                    else -> curves += 1
                }
            } else {
                curves += 1
            }
            if (curves > index) {
                return b
            }
        }
        return null
    }
}

class AnimationEvent(reader: ObjectReader) {
    val time: Float = reader.readSingle()
    val functionName: String = reader.readAlignedString()
    val data: String = reader.readAlignedString()
    val objectReferenceParameter: PPtr = PPtr(reader)
    val floatParameter: Float = reader.readSingle()
    var intParameter: Int = 0
    val messageOptions: Int

    init {
        val version = reader.version
        if (version[0] >= 3) { //3 and up
            intParameter = reader.readInt32()
        }
        messageOptions = reader.readInt32()
    }
}

enum class AnimationType(val value: Int) {
    Legacy(1),
    Generic(2),
    Humanoid(3);

    companion object {
        fun fromValue(value: Int): AnimationType = entries.firstOrNull { it.value == value } ?: Legacy
    }
}

class AnimationClip(reader: ObjectReader) : NamedObject(reader) {
    var m_AnimationType: AnimationType? = null
    var m_Legacy: Boolean = false
    var m_Compressed: Boolean = false
    var m_UseHighQualityCurve: Boolean = false
    var m_RotationCurves: Array<QuaternionCurve> = emptyArray()
    var m_CompressedRotationCurves: Array<CompressedAnimationCurve> = emptyArray()
    var m_EulerCurves: Array<Vector3Curve>? = null
    var m_PositionCurves: Array<Vector3Curve> = emptyArray()
    var m_ScaleCurves: Array<Vector3Curve> = emptyArray()
    var m_FloatCurves: Array<FloatCurve> = emptyArray()
    var m_PPtrCurves: Array<PPtrCurve>? = null
    var m_SampleRate: Float = 0f
    var m_WrapMode: Int = 0
    var m_Bounds: com.assetstudio.mobile.core.math.AABB? = null
    var m_MuscleClipSize: Long = 0 //uint
    var m_MuscleClip: ClipMuscleConstant? = null
    var m_ClipBindingConstant: AnimationClipBindingConstant? = null
    var m_Events: Array<AnimationEvent> = emptyArray()

    init {
        if (version[0] >= 5) { //5.0 and up
            m_Legacy = reader.readBoolean()
        } else if (version[0] >= 4) { //4.0 and up
            m_AnimationType = AnimationType.fromValue(reader.readInt32())
            if (m_AnimationType == AnimationType.Legacy) {
                m_Legacy = true
            }
        } else {
            m_Legacy = true
        }
        m_Compressed = reader.readBoolean()
        if (version[0] > 4 || (version[0] == 4 && version[1] >= 3)) { //4.3 and up
            m_UseHighQualityCurve = reader.readBoolean()
        }
        reader.alignStream()

        m_RotationCurves = Array(reader.readArrayCount()) { QuaternionCurve(reader) }

        m_CompressedRotationCurves = Array(reader.readArrayCount()) { CompressedAnimationCurve(reader) }

        if (version[0] > 5 || (version[0] == 5 && version[1] >= 3)) { //5.3 and up
            m_EulerCurves = Array(reader.readArrayCount()) { Vector3Curve(reader) }
        }

        m_PositionCurves = Array(reader.readArrayCount()) { Vector3Curve(reader) }

        m_ScaleCurves = Array(reader.readArrayCount()) { Vector3Curve(reader) }

        m_FloatCurves = Array(reader.readArrayCount()) { FloatCurve(reader) }

        if (version[0] > 4 || (version[0] == 4 && version[1] >= 3)) { //4.3 and up
            m_PPtrCurves = Array(reader.readArrayCount()) { PPtrCurve(reader) }
        }

        m_SampleRate = reader.readSingle()
        m_WrapMode = reader.readInt32()
        if (version[0] > 3 || (version[0] == 3 && version[1] >= 4)) { //3.4 and up
            m_Bounds = reader.readAABB()
        }
        if (version[0] >= 4) { //4.0 and up
            m_MuscleClipSize = reader.readUInt32()
            m_MuscleClip = ClipMuscleConstant(reader)
        }
        if (version[0] > 4 || (version[0] == 4 && version[1] >= 3)) { //4.3 and up
            m_ClipBindingConstant = AnimationClipBindingConstant(reader)
        }
        if (version[0] > 2018 || (version[0] == 2018 && version[1] >= 3)) { //2018.3 and up
            val m_HasGenericRootTransform = reader.readBoolean()
            val m_HasMotionFloatCurves = reader.readBoolean()
            reader.alignStream()
        }
        m_Events = Array(reader.readArrayCount()) { AnimationEvent(reader) }
        if (version[0] >= 2017) { //2017 and up
            reader.alignStream()
        }
    }
}

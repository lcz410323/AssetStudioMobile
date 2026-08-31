package com.assetstudio.mobile.core.classes

/*
 * 来源: AssetStudio/Classes/Mesh.cs（完整移植）
 * - 版本分支、VertexData/CompressedMesh/StreamInfo/SubMesh 均保留 C# 逻辑结构
 * - m_Skin/rigidbody 等中间引用按 C# 原样处理（跳过或按需读取）
 * - MeshData 访问器（GetUV 等）保留，数据字段直接暴露
 */

import com.assetstudio.mobile.core.ObjectReader
import com.assetstudio.mobile.core.io.EndianType
import com.assetstudio.mobile.core.math.AABB
import com.assetstudio.mobile.core.math.Half
import com.assetstudio.mobile.core.math.Matrix4x4
import com.assetstudio.mobile.core.math.Vector3
import com.assetstudio.mobile.core.resourceReaderOfStream
import kotlin.math.sqrt

class MinMaxAABB(reader: ObjectReader) {
    val m_Min: Vector3 = reader.readVector3()
    val m_Max: Vector3 = reader.readVector3()
}

class CompressedMesh(reader: ObjectReader) {
    val m_Vertices: PackedFloatVector
    val m_UV: PackedFloatVector
    val m_BindPoses: PackedFloatVector?
    val m_Normals: PackedFloatVector
    val m_Tangents: PackedFloatVector
    val m_Weights: PackedIntVector
    val m_NormalSigns: PackedIntVector
    val m_TangentSigns: PackedIntVector
    val m_FloatColors: PackedFloatVector?
    val m_BoneIndices: PackedIntVector
    val m_Triangles: PackedIntVector
    val m_Colors: PackedIntVector?
    var m_UVInfo: Long = 0 //uint

    init {
        val version = reader.version

        m_Vertices = PackedFloatVector(reader)
        m_UV = PackedFloatVector(reader)
        m_BindPoses = if (version[0] < 5) {
            PackedFloatVector(reader)
        } else {
            null
        }
        m_Normals = PackedFloatVector(reader)
        m_Tangents = PackedFloatVector(reader)
        m_Weights = PackedIntVector(reader)
        m_NormalSigns = PackedIntVector(reader)
        m_TangentSigns = PackedIntVector(reader)
        m_FloatColors = if (version[0] >= 5) {
            PackedFloatVector(reader)
        } else {
            null
        }
        m_BoneIndices = PackedIntVector(reader)
        m_Triangles = PackedIntVector(reader)
        if (version[0] > 3 || (version[0] == 3 && version[1] >= 5)) { //3.5 and up
            if (version[0] < 5) {
                m_Colors = PackedIntVector(reader)
            } else {
                m_UVInfo = reader.readUInt32()
                m_Colors = null
            }
        } else {
            m_Colors = null
        }
    }
}

class StreamInfo {
    var channelMask: Long = 0 //uint
    var offset: Long = 0 //uint
    var stride: Long = 0 //uint
    var align: Long = 0 //uint
    var dividerOp: Int = 0 //byte
    var frequency: Int = 0 //ushort

    constructor()

    constructor(reader: ObjectReader) {
        val version = reader.version

        channelMask = reader.readUInt32()
        offset = reader.readUInt32()

        if (version[0] < 4) { //4.0 down
            stride = reader.readUInt32()
            align = reader.readUInt32()
        } else {
            stride = reader.readUInt8().toLong()
            dividerOp = reader.readUInt8()
            frequency = reader.readUInt16()
        }
    }
}

class ChannelInfo {
    var stream: Int = 0 //byte
    var offset: Int = 0 //byte
    var format: Int = 0 //byte
    var dimension: Int = 0 //byte & 0xF

    constructor()

    constructor(reader: ObjectReader) {
        stream = reader.readUInt8()
        offset = reader.readUInt8()
        format = reader.readUInt8()
        dimension = reader.readUInt8() and 0xF
    }
}

class VertexData(reader: ObjectReader) {
    var m_CurrentChannels: Long = 0 //uint
    var m_VertexCount: Long = 0 //uint
    var m_Channels: Array<ChannelInfo> = emptyArray()
    var m_Streams: Array<StreamInfo> = emptyArray()
    var m_DataSize: ByteArray = ByteArray(0)

    init {
        val version = reader.version

        if (version[0] < 2018) { //2018 down
            m_CurrentChannels = reader.readUInt32()
        }

        m_VertexCount = reader.readUInt32()

        if (version[0] >= 4) { //4.0 and up
            val m_ChannelsSize = reader.readArrayCount()
            m_Channels = Array(m_ChannelsSize) { ChannelInfo(reader) }
        }

        if (version[0] < 5) { //5.0 down
            m_Streams = if (version[0] < 4) {
                Array(4) { StreamInfo() }
            } else {
                Array(reader.readArrayCount()) { StreamInfo() }
            }

            for (i in m_Streams.indices) {
                m_Streams[i] = StreamInfo(reader)
            }

            if (version[0] < 4) { //4.0 down
                getChannels(version)
            }
        } else { //5.0 and up
            getStreams(version)
        }

        m_DataSize = reader.readUInt8Array()
        reader.alignStream()
    }

    private fun getStreams(version: IntArray) {
        val streamCount = m_Channels.maxOf { it.stream } + 1
        m_Streams = Array(streamCount) { StreamInfo() }
        var offset = 0L
        for (s in 0 until streamCount) {
            var chnMask = 0L
            var stride = 0L
            for (chn in m_Channels.indices) {
                val m_Channel = m_Channels[chn]
                if (m_Channel.stream == s) {
                    if (m_Channel.dimension > 0) {
                        chnMask = chnMask or (1L shl chn)
                        stride += m_Channel.dimension * MeshHelper.getFormatSize(MeshHelper.toVertexFormat(m_Channel.format, version))
                    }
                }
            }
            val si = StreamInfo()
            si.channelMask = chnMask
            si.offset = offset
            si.stride = stride
            si.dividerOp = 0
            si.frequency = 0
            m_Streams[s] = si
            offset += m_VertexCount * stride
            //static size_t AlignStreamSize (size_t size) { return (size + (kVertexStreamAlign-1)) & ~(kVertexStreamAlign-1); }
            offset = (offset + (16L - 1L)) and (16L - 1L).inv()
        }
    }

    private fun getChannels(version: IntArray) {
        m_Channels = Array(6) { ChannelInfo() }
        for (s in m_Streams.indices) {
            val m_Stream = m_Streams[s]
            val channelMask = m_Stream.channelMask
            var offset = 0
            for (i in 0 until 6) {
                if (channelMask and (1L shl i) != 0L) { //等价 C# BitArray.Get(i)
                    val m_Channel = m_Channels[i]
                    m_Channel.stream = s
                    m_Channel.offset = offset
                    when (i) {
                        0, 1 -> { //kShaderChannelVertex / kShaderChannelNormal
                            m_Channel.format = 0 //kChannelFormatFloat
                            m_Channel.dimension = 3
                        }
                        2 -> { //kShaderChannelColor
                            m_Channel.format = 2 //kChannelFormatColor
                            m_Channel.dimension = 4
                        }
                        3, 4 -> { //kShaderChannelTexCoord0 / kShaderChannelTexCoord1
                            m_Channel.format = 0 //kChannelFormatFloat
                            m_Channel.dimension = 2
                        }
                        5 -> { //kShaderChannelTangent
                            m_Channel.format = 0 //kChannelFormatFloat
                            m_Channel.dimension = 4
                        }
                    }
                    offset += m_Channel.dimension * MeshHelper.getFormatSize(MeshHelper.toVertexFormat(m_Channel.format, version))
                }
            }
        }
    }
}

class BoneWeights4 {
    var weight: FloatArray
    var boneIndex: IntArray

    constructor() {
        weight = FloatArray(4)
        boneIndex = IntArray(4)
    }

    constructor(reader: ObjectReader) {
        weight = reader.readSingleArray(4)
        boneIndex = reader.readInt32Array(4)
    }
}

class BlendShapeVertex(reader: ObjectReader) {
    val vertex: Vector3 = reader.readVector3()
    val normal: Vector3 = reader.readVector3()
    val tangent: Vector3 = reader.readVector3()
    val index: Long = reader.readUInt32()
}

class MeshBlendShape(reader: ObjectReader) {
    val firstVertex: Long
    val vertexCount: Long
    val hasNormals: Boolean
    val hasTangents: Boolean

    init {
        val version = reader.version

        if (version[0] == 4 && version[1] < 3) { //4.3 down
            val name = reader.readAlignedString()
        }
        firstVertex = reader.readUInt32()
        vertexCount = reader.readUInt32()
        if (version[0] == 4 && version[1] < 3) { //4.3 down
            val aabbMinDelta = reader.readVector3()
            val aabbMaxDelta = reader.readVector3()
        }
        hasNormals = reader.readBoolean()
        hasTangents = reader.readBoolean()
        if (version[0] > 4 || (version[0] == 4 && version[1] >= 3)) { //4.3 and up
            reader.alignStream()
        }
    }
}

class MeshBlendShapeChannel(reader: ObjectReader) {
    val name: String = reader.readAlignedString()
    val nameHash: Long = reader.readUInt32()
    val frameIndex: Int = reader.readInt32()
    val frameCount: Int = reader.readInt32()
}

class BlendShapeData(reader: ObjectReader) {
    val vertices: Array<BlendShapeVertex>?
    val shapes: Array<MeshBlendShape>?
    val channels: Array<MeshBlendShapeChannel>?
    val fullWeights: FloatArray?

    init {
        val version = reader.version

        if (version[0] > 4 || (version[0] == 4 && version[1] >= 3)) { //4.3 and up
            val numVerts = reader.readArrayCount()
            vertices = Array(numVerts) { BlendShapeVertex(reader) }

            val numShapes = reader.readArrayCount()
            shapes = Array(numShapes) { MeshBlendShape(reader) }

            val numChannels = reader.readArrayCount()
            channels = Array(numChannels) { MeshBlendShapeChannel(reader) }

            fullWeights = reader.readSingleArray()
        } else {
            vertices = null
            shapes = null
            channels = null
            fullWeights = null
            val m_ShapesSize = reader.readArrayCount()
            val m_Shapes = Array(m_ShapesSize) { MeshBlendShape(reader) }
            reader.alignStream()
            val m_ShapeVerticesSize = reader.readArrayCount()
            val m_ShapeVertices = Array(m_ShapeVerticesSize) { BlendShapeVertex(reader) } //MeshBlendShapeVertex
        }
    }
}

enum class GfxPrimitiveType(val value: Int) {
    Triangles(0),
    TriangleStrip(1),
    Quads(2),
    Lines(3),
    LineStrip(4),
    Points(5);

    companion object {
        fun fromValue(value: Int): GfxPrimitiveType =
            entries.firstOrNull { it.value == value } ?: Triangles
    }
}

class SubMesh(reader: ObjectReader) {
    val firstByte: Long
    var indexCount: Long
    val topology: GfxPrimitiveType
    var triangleCount: Long = 0
    var baseVertex: Long = 0
    var firstVertex: Long = 0
    var vertexCount: Long = 0
    var localAABB: AABB? = null

    init {
        val version = reader.version

        firstByte = reader.readUInt32()
        indexCount = reader.readUInt32()
        topology = GfxPrimitiveType.fromValue(reader.readInt32())

        if (version[0] < 4) { //4.0 down
            triangleCount = reader.readUInt32()
        }

        if (version[0] > 2017 || (version[0] == 2017 && version[1] >= 3)) { //2017.3 and up
            baseVertex = reader.readUInt32()
        }

        if (version[0] >= 3) { //3.0 and up
            firstVertex = reader.readUInt32()
            vertexCount = reader.readUInt32()
            localAABB = reader.readAABB()
        }
    }
}

class Mesh(reader: ObjectReader) : NamedObject(reader) {
    private var m_Use16BitIndices = true
    var m_SubMeshes: Array<SubMesh> = emptyArray()
    private var m_IndexBuffer = LongArray(0)
    var m_Shapes: BlendShapeData? = null
    var m_BindPose: Array<Matrix4x4>? = null
    var m_BoneNameHashes: LongArray? = null
    var m_VertexCount: Int = 0
    var m_Vertices: FloatArray? = null
    var m_Skin: Array<BoneWeights4>? = null
    var m_Normals: FloatArray? = null
    var m_Colors: FloatArray? = null
    var m_UV0: FloatArray? = null
    var m_UV1: FloatArray? = null
    var m_UV2: FloatArray? = null
    var m_UV3: FloatArray? = null
    var m_UV4: FloatArray? = null
    var m_UV5: FloatArray? = null
    var m_UV6: FloatArray? = null
    var m_UV7: FloatArray? = null
    var m_Tangents: FloatArray? = null
    var m_VertexData: VertexData? = null
    var m_CompressedMesh: CompressedMesh? = null
    var m_StreamData: StreamingInfo? = null

    val m_Indices = ArrayList<Long>()

    init {
        if (version[0] < 3 || (version[0] == 3 && version[1] < 5)) { //3.5 down
            m_Use16BitIndices = reader.readInt32() > 0
        }

        if (version[0] == 2 && version[1] <= 5) { //2.5 and down
            val m_IndexBuffer_size = reader.readInt32()

            if (m_Use16BitIndices) {
                m_IndexBuffer = LongArray(m_IndexBuffer_size / 2)
                for (i in 0 until m_IndexBuffer_size / 2) {
                    m_IndexBuffer[i] = reader.readUInt16().toLong()
                }
                reader.alignStream()
            } else {
                m_IndexBuffer = readUInt32Array(reader, m_IndexBuffer_size / 4)
            }
        }

        val m_SubMeshesSize = reader.readArrayCount()
        m_SubMeshes = Array(m_SubMeshesSize) { SubMesh(reader) }

        if (version[0] > 4 || (version[0] == 4 && version[1] >= 1)) { //4.1 and up
            m_Shapes = BlendShapeData(reader)
        }

        if (version[0] > 4 || (version[0] == 4 && version[1] >= 3)) { //4.3 and up
            m_BindPose = reader.readMatrixArray()
            m_BoneNameHashes = reader.readUInt32Array()
            val m_RootBoneNameHash = reader.readUInt32()
        }

        if (version[0] > 2 || (version[0] == 2 && version[1] >= 6)) { //2.6.0 and up
            if (version[0] >= 2019) { //2019 and up
                val m_BonesAABBSize = reader.readArrayCount()
                val m_BonesAABB = Array(m_BonesAABBSize) { MinMaxAABB(reader) }

                val m_VariableBoneCountWeights = reader.readUInt32Array()
            }

            val m_MeshCompression = reader.readUInt8()
            if (version[0] >= 4) {
                if (version[0] < 5) {
                    val m_StreamCompression = reader.readUInt8()
                }
                val m_IsReadable = reader.readBoolean()
                val m_KeepVertices = reader.readBoolean()
                val m_KeepIndices = reader.readBoolean()
            }
            reader.alignStream()

            //Unity fixed it in 2017.3.1p1 and later versions
            if ((version[0] > 2017 || (version[0] == 2017 && version[1] >= 4)) || //2017.4
                ((version[0] == 2017 && version[1] == 3 && version[2] == 1) && buildType?.isPatch == true) || //fixed after 2017.3.1px
                ((version[0] == 2017 && version[1] == 3) && m_MeshCompression == 0)
            ) { //2017.3.xfx with no compression
                val m_IndexFormat = reader.readInt32()
                m_Use16BitIndices = m_IndexFormat == 0
            }

            val m_IndexBuffer_size = reader.readInt32()
            if (m_Use16BitIndices) {
                m_IndexBuffer = LongArray(m_IndexBuffer_size / 2)
                for (i in 0 until m_IndexBuffer_size / 2) {
                    m_IndexBuffer[i] = reader.readUInt16().toLong()
                }
                reader.alignStream()
            } else {
                m_IndexBuffer = readUInt32Array(reader, m_IndexBuffer_size / 4)
            }
        }

        if (version[0] < 3 || (version[0] == 3 && version[1] < 5)) { //3.4.2 and earlier
            m_VertexCount = reader.readInt32()
            m_Vertices = reader.readSingleArray(m_VertexCount * 3) //Vector3

            m_Skin = Array(reader.readArrayCount()) { BoneWeights4(reader) }

            m_BindPose = reader.readMatrixArray()

            m_UV0 = reader.readSingleArray(reader.readInt32() * 2) //Vector2

            m_UV1 = reader.readSingleArray(reader.readInt32() * 2) //Vector2

            if (version[0] == 2 && version[1] <= 5) { //2.5 and down
                val m_TangentSpace_size = reader.readInt32()
                m_Normals = FloatArray(m_TangentSpace_size * 3)
                m_Tangents = FloatArray(m_TangentSpace_size * 4)
                for (v in 0 until m_TangentSpace_size) {
                    m_Normals!![v * 3] = reader.readSingle()
                    m_Normals!![v * 3 + 1] = reader.readSingle()
                    m_Normals!![v * 3 + 2] = reader.readSingle()
                    m_Tangents!![v * 3] = reader.readSingle()
                    m_Tangents!![v * 3 + 1] = reader.readSingle()
                    m_Tangents!![v * 3 + 2] = reader.readSingle()
                    m_Tangents!![v * 3 + 3] = reader.readSingle() //handedness
                }
            } else { //2.6.0 and later
                m_Tangents = reader.readSingleArray(reader.readInt32() * 4) //Vector4

                m_Normals = reader.readSingleArray(reader.readInt32() * 3) //Vector3
            }
        } else {
            if (version[0] < 2018 || (version[0] == 2018 && version[1] < 2)) { //2018.2 down
                m_Skin = Array(reader.readArrayCount()) { BoneWeights4(reader) }
            }

            if (version[0] == 3 || (version[0] == 4 && version[1] <= 2)) { //4.2 and down
                m_BindPose = reader.readMatrixArray()
            }

            m_VertexData = VertexData(reader)
        }

        if (version[0] > 2 || (version[0] == 2 && version[1] >= 6)) { //2.6.0 and later
            m_CompressedMesh = CompressedMesh(reader)
        }

        reader.position += 24 //AABB m_LocalAABB

        if (version[0] < 3 || (version[0] == 3 && version[1] <= 4)) { //3.4.2 and earlier
            val m_Colors_size = reader.readInt32()
            m_Colors = FloatArray(m_Colors_size * 4)
            for (v in 0 until m_Colors_size * 4) {
                m_Colors!![v] = reader.readUInt8() / 0xFF.toFloat()
            }

            val m_CollisionTriangles_size = reader.readInt32()
            reader.position += m_CollisionTriangles_size * 4 //UInt32 indices
            val m_CollisionVertexCount = reader.readInt32()
        }

        val m_MeshUsageFlags = reader.readInt32()

        if (version[0] > 2022 || (version[0] == 2022 && version[1] >= 1)) { //2022.1 and up
            val m_CookingOptions = reader.readInt32()
        }

        if (version[0] >= 5) { //5.0 and up
            val m_BakedConvexCollisionMesh = reader.readUInt8Array()
            reader.alignStream()
            val m_BakedTriangleCollisionMesh = reader.readUInt8Array()
            reader.alignStream()
        }

        if (version[0] > 2018 || (version[0] == 2018 && version[1] >= 2)) { //2018.2 and up
            val m_MeshMetrics = FloatArray(2)
            m_MeshMetrics[0] = reader.readSingle()
            m_MeshMetrics[1] = reader.readSingle()
        }

        if (version[0] > 2018 || (version[0] == 2018 && version[1] >= 3)) { //2018.3 and up
            reader.alignStream()
            m_StreamData = StreamingInfo(reader)
        }

        processData()
    }

    private fun processData() {
        val streamData = m_StreamData
        val vertexData = m_VertexData
        if (streamData != null && streamData.path.isNotEmpty()) {
            if (vertexData != null && vertexData.m_VertexCount > 0) {
                val resourceReader = resourceReaderOfStream(assetsFile, streamData.path, streamData.offset, streamData.size)
                vertexData.m_DataSize = resourceReader.getData()
            }
        }
        if (version[0] > 3 || (version[0] == 3 && version[1] >= 5)) { //3.5 and up
            readVertexData()
        }

        if (version[0] > 2 || (version[0] == 2 && version[1] >= 6)) { //2.6.0 and later
            decompressCompressedMesh()
        }

        getTriangles()
    }

    private fun readVertexData() {
        val vertexData = m_VertexData ?: return
        m_VertexCount = vertexData.m_VertexCount.toInt()

        for (chn in vertexData.m_Channels.indices) {
            val m_Channel = vertexData.m_Channels[chn]
            if (m_Channel.dimension > 0) {
                val m_Stream = vertexData.m_Streams[m_Channel.stream]
                if (m_Stream.channelMask and (1L shl chn) != 0L) { //等价 C# BitArray.Get(chn)
                    if (version[0] < 2018 && chn == 2 && m_Channel.format == 2) { //kShaderChannelColor && kChannelFormatColor
                        m_Channel.dimension = 4
                    }

                    val vertexFormat = MeshHelper.toVertexFormat(m_Channel.format, version)
                    val componentByteSize = MeshHelper.getFormatSize(vertexFormat)
                    val componentBytes = ByteArray(m_VertexCount * m_Channel.dimension * componentByteSize)
                    for (v in 0 until m_VertexCount) {
                        val vertexOffset = m_Stream.offset.toInt() + m_Channel.offset + m_Stream.stride.toInt() * v
                        for (d in 0 until m_Channel.dimension) {
                            val componentOffset = vertexOffset + componentByteSize * d
                            System.arraycopy(
                                vertexData.m_DataSize, componentOffset,
                                componentBytes, componentByteSize * (v * m_Channel.dimension + d),
                                componentByteSize
                            )
                        }
                    }

                    if (reader.endian == EndianType.BigEndian && componentByteSize > 1) { //swap bytes
                        var i = 0
                        while (i < componentBytes.size / componentByteSize) {
                            val base = i * componentByteSize
                            var left = 0
                            var right = componentByteSize - 1
                            while (left < right) {
                                val tmp = componentBytes[base + left]
                                componentBytes[base + left] = componentBytes[base + right]
                                componentBytes[base + right] = tmp
                                left++
                                right--
                            }
                            i++
                        }
                    }

                    var componentsIntArray: IntArray? = null
                    var componentsFloatArray: FloatArray? = null
                    if (MeshHelper.isIntFormat(vertexFormat)) {
                        componentsIntArray = MeshHelper.bytesToIntArray(componentBytes, vertexFormat)
                    } else {
                        componentsFloatArray = MeshHelper.bytesToFloatArray(componentBytes, vertexFormat)
                    }

                    if (version[0] >= 2018) {
                        when (chn) {
                            0 -> m_Vertices = componentsFloatArray //kShaderChannelVertex
                            1 -> m_Normals = componentsFloatArray //kShaderChannelNormal
                            2 -> m_Tangents = componentsFloatArray //kShaderChannelTangent
                            3 -> m_Colors = componentsFloatArray //kShaderChannelColor
                            4 -> m_UV0 = componentsFloatArray //kShaderChannelTexCoord0
                            5 -> m_UV1 = componentsFloatArray //kShaderChannelTexCoord1
                            6 -> m_UV2 = componentsFloatArray //kShaderChannelTexCoord2
                            7 -> m_UV3 = componentsFloatArray //kShaderChannelTexCoord3
                            8 -> m_UV4 = componentsFloatArray //kShaderChannelTexCoord4
                            9 -> m_UV5 = componentsFloatArray //kShaderChannelTexCoord5
                            10 -> m_UV6 = componentsFloatArray //kShaderChannelTexCoord6
                            11 -> m_UV7 = componentsFloatArray //kShaderChannelTexCoord7
                            //2018.2 and up
                            12 -> { //kShaderChannelBlendWeight
                                if (m_Skin == null) {
                                    initMSkin()
                                }
                                val skin = m_Skin!!
                                for (i in 0 until m_VertexCount) {
                                    for (j in 0 until m_Channel.dimension) {
                                        skin[i].weight[j] = componentsFloatArray!![i * m_Channel.dimension + j]
                                    }
                                }
                            }
                            13 -> { //kShaderChannelBlendIndices
                                if (m_Skin == null) {
                                    initMSkin()
                                }
                                val skin = m_Skin!!
                                for (i in 0 until m_VertexCount) {
                                    for (j in 0 until m_Channel.dimension) {
                                        skin[i].boneIndex[j] = componentsIntArray!![i * m_Channel.dimension + j]
                                    }
                                }
                            }
                        }
                    } else {
                        when (chn) {
                            0 -> m_Vertices = componentsFloatArray //kShaderChannelVertex
                            1 -> m_Normals = componentsFloatArray //kShaderChannelNormal
                            2 -> m_Colors = componentsFloatArray //kShaderChannelColor
                            3 -> m_UV0 = componentsFloatArray //kShaderChannelTexCoord0
                            4 -> m_UV1 = componentsFloatArray //kShaderChannelTexCoord1
                            5 -> {
                                if (version[0] >= 5) { //kShaderChannelTexCoord2
                                    m_UV2 = componentsFloatArray
                                } else { //kShaderChannelTangent
                                    m_Tangents = componentsFloatArray
                                }
                            }
                            6 -> m_UV3 = componentsFloatArray //kShaderChannelTexCoord3
                            7 -> m_Tangents = componentsFloatArray //kShaderChannelTangent
                        }
                    }
                }
            }
        }
    }

    private fun decompressCompressedMesh() {
        val compressedMesh = m_CompressedMesh ?: return
        //Vertex
        if (compressedMesh.m_Vertices.m_NumItems > 0) {
            m_VertexCount = compressedMesh.m_Vertices.m_NumItems.toInt() / 3
            m_Vertices = compressedMesh.m_Vertices.unpackFloats(3, 3 * 4)
        }
        //UV
        if (compressedMesh.m_UV.m_NumItems > 0) {
            val m_UVInfo = compressedMesh.m_UVInfo
            if (m_UVInfo != 0L) {
                val kInfoBitsPerUV = 4
                val kUVDimensionMask = 3
                val kUVChannelExists = 4
                val kMaxTexCoordShaderChannels = 8

                var uvSrcOffset = 0
                for (uv in 0 until kMaxTexCoordShaderChannels) {
                    var texCoordBits = (m_UVInfo shr (uv * kInfoBitsPerUV)).toInt()
                    texCoordBits = texCoordBits and ((1 shl kInfoBitsPerUV) - 1)
                    if (texCoordBits and kUVChannelExists != 0) {
                        val uvDim = 1 + (texCoordBits and kUVDimensionMask)
                        val m_UV = compressedMesh.m_UV.unpackFloats(uvDim, uvDim * 4, uvSrcOffset, m_VertexCount)
                        setUV(uv, m_UV)
                        uvSrcOffset += uvDim * m_VertexCount
                    }
                }
            } else {
                m_UV0 = compressedMesh.m_UV.unpackFloats(2, 2 * 4, 0, m_VertexCount)
                if (compressedMesh.m_UV.m_NumItems >= m_VertexCount * 4L) {
                    m_UV1 = compressedMesh.m_UV.unpackFloats(2, 2 * 4, m_VertexCount * 2, m_VertexCount)
                }
            }
        }
        //BindPose
        if (version[0] < 5) {
            val bindPoses = compressedMesh.m_BindPoses
            if (bindPoses != null && bindPoses.m_NumItems > 0) {
                val bindPoseArray = Array(bindPoses.m_NumItems.toInt() / 16) { Matrix4x4(FloatArray(16)) }
                val m_BindPoses_Unpacked = bindPoses.unpackFloats(16, 4 * 16)
                val buffer = FloatArray(16)
                for (i in bindPoseArray.indices) {
                    System.arraycopy(m_BindPoses_Unpacked, i * 16, buffer, 0, 16)
                    bindPoseArray[i] = Matrix4x4(buffer.copyOf())
                }
                m_BindPose = bindPoseArray
            }
        }
        //Normal
        if (compressedMesh.m_Normals.m_NumItems > 0) {
            val normalData = compressedMesh.m_Normals.unpackFloats(2, 4 * 2)
            val signs = compressedMesh.m_NormalSigns.unpackInts()
            m_Normals = FloatArray(compressedMesh.m_Normals.m_NumItems.toInt() / 2 * 3)
            val normals = m_Normals!!
            for (i in 0 until compressedMesh.m_Normals.m_NumItems.toInt() / 2) {
                var x = normalData[i * 2 + 0]
                var y = normalData[i * 2 + 1]
                val zsqr = 1 - x * x - y * y
                var z: Float
                if (zsqr >= 0f) {
                    z = sqrt(zsqr)
                } else {
                    z = 0f
                    //等价 C# normal.Normalize()
                    val len = sqrt(x * x + y * y)
                    if (len > 0f) {
                        x /= len
                        y /= len
                    }
                    z = 0f
                }
                if (signs[i] == 0) {
                    z = -z
                }
                normals[i * 3] = x
                normals[i * 3 + 1] = y
                normals[i * 3 + 2] = z
            }
        }
        //Tangent
        if (compressedMesh.m_Tangents.m_NumItems > 0) {
            val tangentData = compressedMesh.m_Tangents.unpackFloats(2, 4 * 2)
            val signs = compressedMesh.m_TangentSigns.unpackInts()
            m_Tangents = FloatArray(compressedMesh.m_Tangents.m_NumItems.toInt() / 2 * 4)
            val tangents = m_Tangents!!
            for (i in 0 until compressedMesh.m_Tangents.m_NumItems.toInt() / 2) {
                var x = tangentData[i * 2 + 0]
                var y = tangentData[i * 2 + 1]
                val zsqr = 1 - x * x - y * y
                var z: Float
                if (zsqr >= 0f) {
                    z = sqrt(zsqr)
                } else {
                    z = 0f
                    val len = sqrt(x * x + y * y)
                    if (len > 0f) {
                        x /= len
                        y /= len
                    }
                    z = 0f
                }
                if (signs[i * 2 + 0] == 0) {
                    z = -z
                }
                val w = if (signs[i * 2 + 1] > 0) 1.0f else -1.0f
                tangents[i * 4] = x
                tangents[i * 4 + 1] = y
                tangents[i * 4 + 2] = z
                tangents[i * 4 + 3] = w
            }
        }
        //FloatColor
        if (version[0] >= 5) {
            val floatColors = compressedMesh.m_FloatColors
            if (floatColors != null && floatColors.m_NumItems > 0) {
                m_Colors = floatColors.unpackFloats(1, 4)
            }
        }
        //Skin
        if (compressedMesh.m_Weights.m_NumItems > 0) {
            val weights = compressedMesh.m_Weights.unpackInts()
            val boneIndices = compressedMesh.m_BoneIndices.unpackInts()

            initMSkin()
            val skin = m_Skin!!

            var bonePos = 0
            var boneIndexPos = 0
            var j = 0
            var sum = 0

            for (i in 0 until compressedMesh.m_Weights.m_NumItems.toInt()) {
                //read bone index and weight.
                skin[bonePos].weight[j] = weights[i] / 31.0f
                skin[bonePos].boneIndex[j] = boneIndices[boneIndexPos++]
                j++
                sum += weights[i]

                //the weights add up to one. fill the rest for this vertex with zero, and continue with next one.
                if (sum >= 31) {
                    while (j < 4) {
                        skin[bonePos].weight[j] = 0f
                        skin[bonePos].boneIndex[j] = 0
                        j++
                    }
                    bonePos++
                    j = 0
                    sum = 0
                }
                //we read three weights, but they don't add up to one. calculate the fourth one, and read
                //missing bone index. continue with next vertex.
                else if (j == 3) {
                    skin[bonePos].weight[j] = (31 - sum) / 31.0f
                    skin[bonePos].boneIndex[j] = boneIndices[boneIndexPos++]
                    bonePos++
                    j = 0
                    sum = 0
                }
            }
        }
        //IndexBuffer
        if (compressedMesh.m_Triangles.m_NumItems > 0) {
            m_IndexBuffer = compressedMesh.m_Triangles.unpackInts().map { it.toLong() }.toLongArray()
        }
        //Color
        val colors = compressedMesh.m_Colors
        if (colors != null && colors.m_NumItems > 0) {
            colors.m_NumItems *= 4
            colors.m_BitSize /= 4
            val tempColors = colors.unpackInts()
            m_Colors = FloatArray(colors.m_NumItems.toInt())
            val colorArray = m_Colors!!
            for (v in 0 until colors.m_NumItems.toInt()) {
                colorArray[v] = tempColors[v] / 255f
            }
        }
    }

    private fun getTriangles() {
        for (m_SubMesh in m_SubMeshes) {
            var firstIndex = (m_SubMesh.firstByte / 2).toInt()
            if (!m_Use16BitIndices) {
                firstIndex /= 2
            }
            val indexCount = m_SubMesh.indexCount.toInt()
            val topology = m_SubMesh.topology
            if (topology == GfxPrimitiveType.Triangles) {
                var i = 0
                while (i < indexCount) {
                    m_Indices.add(m_IndexBuffer[firstIndex + i])
                    m_Indices.add(m_IndexBuffer[firstIndex + i + 1])
                    m_Indices.add(m_IndexBuffer[firstIndex + i + 2])
                    i += 3
                }
            } else if (version[0] < 4 || topology == GfxPrimitiveType.TriangleStrip) {
                // de-stripify :
                var triIndex = 0L
                for (i in 0 until indexCount - 2) {
                    val a = m_IndexBuffer[firstIndex + i]
                    val b = m_IndexBuffer[firstIndex + i + 1]
                    val c = m_IndexBuffer[firstIndex + i + 2]

                    // skip degenerates
                    if (a == b || a == c || b == c) {
                        continue
                    }

                    // do the winding flip-flop of strips :
                    if (i and 1 == 1) {
                        m_Indices.add(b)
                        m_Indices.add(a)
                    } else {
                        m_Indices.add(a)
                        m_Indices.add(b)
                    }
                    m_Indices.add(c)
                    triIndex += 3
                }
                //fix indexCount
                m_SubMesh.indexCount = triIndex
            } else if (topology == GfxPrimitiveType.Quads) {
                var q = 0
                while (q < indexCount) {
                    m_Indices.add(m_IndexBuffer[firstIndex + q])
                    m_Indices.add(m_IndexBuffer[firstIndex + q + 1])
                    m_Indices.add(m_IndexBuffer[firstIndex + q + 2])
                    m_Indices.add(m_IndexBuffer[firstIndex + q])
                    m_Indices.add(m_IndexBuffer[firstIndex + q + 2])
                    m_Indices.add(m_IndexBuffer[firstIndex + q + 3])
                    q += 4
                }
                //fix indexCount
                m_SubMesh.indexCount = (indexCount / 2 * 3).toLong()
            } else {
                throw UnsupportedOperationException("Failed getting triangles. Submesh topology is lines or points.")
            }
        }
    }

    private fun initMSkin() {
        m_Skin = Array(m_VertexCount) { BoneWeights4() }
    }

    private fun setUV(uv: Int, m_UV: FloatArray) {
        when (uv) {
            0 -> m_UV0 = m_UV
            1 -> m_UV1 = m_UV
            2 -> m_UV2 = m_UV
            3 -> m_UV3 = m_UV
            4 -> m_UV4 = m_UV
            5 -> m_UV5 = m_UV
            6 -> m_UV6 = m_UV
            7 -> m_UV7 = m_UV
            else -> throw IndexOutOfBoundsException("uv = $uv")
        }
    }

    fun getUV(uv: Int): FloatArray? {
        return when (uv) {
            0 -> m_UV0
            1 -> m_UV1
            2 -> m_UV2
            3 -> m_UV3
            4 -> m_UV4
            5 -> m_UV5
            6 -> m_UV6
            7 -> m_UV7
            else -> throw IndexOutOfBoundsException("uv = $uv")
        }
    }
}

object MeshHelper {

    enum class VertexFormat {
        Float,
        Float16,
        UNorm8,
        SNorm8,
        UNorm16,
        SNorm16,
        UInt8,
        SInt8,
        UInt16,
        SInt16,
        UInt32,
        SInt32
    }

    fun toVertexFormat(format: Int, version: IntArray): VertexFormat {
        if (version[0] < 2017) {
            return when (format) {
                0 -> VertexFormat.Float //VertexChannelFormat.Float
                1 -> VertexFormat.Float16 //VertexChannelFormat.Float16
                2 -> VertexFormat.UNorm8 //VertexChannelFormat.Color, in 4.x is size 4
                3 -> VertexFormat.UInt8 //VertexChannelFormat.Byte
                4 -> VertexFormat.UInt32 //VertexChannelFormat.UInt32, in 5.x
                else -> throw IllegalArgumentException("format = $format")
            }
        } else if (version[0] < 2019) {
            return when (format) {
                0 -> VertexFormat.Float //VertexFormat2017.Float
                1 -> VertexFormat.Float16
                2, 3 -> VertexFormat.UNorm8 //Color / UNorm8
                4 -> VertexFormat.SNorm8
                5 -> VertexFormat.UNorm16
                6 -> VertexFormat.SNorm16
                7 -> VertexFormat.UInt8
                8 -> VertexFormat.SInt8
                9 -> VertexFormat.UInt16
                10 -> VertexFormat.SInt16
                11 -> VertexFormat.UInt32
                12 -> VertexFormat.SInt32
                else -> throw IllegalArgumentException("format = $format")
            }
        } else {
            return VertexFormat.entries.getOrElse(format) { throw IllegalArgumentException("format = $format") }
        }
    }

    fun getFormatSize(format: VertexFormat): Int {
        return when (format) {
            VertexFormat.Float, VertexFormat.UInt32, VertexFormat.SInt32 -> 4
            VertexFormat.Float16, VertexFormat.UNorm16, VertexFormat.SNorm16,
            VertexFormat.UInt16, VertexFormat.SInt16 -> 2
            VertexFormat.UNorm8, VertexFormat.SNorm8, VertexFormat.UInt8, VertexFormat.SInt8 -> 1
        }
    }

    fun isIntFormat(format: VertexFormat): Boolean {
        return format.ordinal >= VertexFormat.UInt8.ordinal
    }

    fun bytesToFloatArray(inputBytes: ByteArray, format: VertexFormat): FloatArray {
        val size = getFormatSize(format)
        val len = inputBytes.size / size
        val result = FloatArray(len)
        for (i in 0 until len) {
            when (format) {
                VertexFormat.Float ->
                    result[i] = Float.fromBits(readIntLE(inputBytes, i * 4))
                VertexFormat.Float16 ->
                    result[i] = Half.toFloat(readUShortLE(inputBytes, i * 2))
                VertexFormat.UNorm8 ->
                    result[i] = (inputBytes[i].toInt() and 0xFF) / 255f
                VertexFormat.SNorm8 ->
                    result[i] = maxOf(inputBytes[i].toInt() / 127f, -1f)
                VertexFormat.UNorm16 ->
                    result[i] = readUShortLE(inputBytes, i * 2) / 65535f
                VertexFormat.SNorm16 ->
                    result[i] = maxOf(readShortLE(inputBytes, i * 2) / 32767f, -1f)
                else -> {}
            }
        }
        return result
    }

    fun bytesToIntArray(inputBytes: ByteArray, format: VertexFormat): IntArray {
        val size = getFormatSize(format)
        val len = inputBytes.size / size
        val result = IntArray(len)
        for (i in 0 until len) {
            when (format) {
                VertexFormat.UInt8, VertexFormat.SInt8 ->
                    result[i] = inputBytes[i].toInt() and 0xFF
                VertexFormat.UInt16, VertexFormat.SInt16 ->
                    result[i] = readShortLE(inputBytes, i * 2).toInt()
                VertexFormat.UInt32, VertexFormat.SInt32 ->
                    result[i] = readIntLE(inputBytes, i * 4)
                else -> {}
            }
        }
        return result
    }

    private fun readShortLE(b: ByteArray, offset: Int): Short =
        ((b[offset + 1].toInt() and 0xFF) shl 8 or (b[offset].toInt() and 0xFF)).toShort()

    private fun readUShortLE(b: ByteArray, offset: Int): Int =
        readShortLE(b, offset).toInt() and 0xFFFF

    private fun readIntLE(b: ByteArray, offset: Int): Int =
        (readUShortLE(b, offset + 2) shl 16) or readUShortLE(b, offset)
}

/** 等价 C# ReadUInt32Array(count)：读取固定数量的 uint */
private fun readUInt32Array(reader: ObjectReader, count: Int): LongArray = LongArray(count) { reader.readUInt32() }

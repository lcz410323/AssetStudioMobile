package com.assetstudio.mobile.core.classes

/*
 * 来源: AssetStudio/Classes/Shader.cs
 * 包含 Shader 序列化结构的全部嵌套类型与版本分支。
 */

import com.assetstudio.mobile.core.ObjectReader

class Hash128(reader: ObjectReader) {
    val bytes: ByteArray = reader.readBytes(16)
}

class StructParameter(reader: ObjectReader) {
    val m_MatrixParams: Array<MatrixParameter>
    val m_VectorParams: Array<VectorParameter>

    init {
        val m_NameIndex = reader.readInt32()
        val m_Index = reader.readInt32()
        val m_ArraySize = reader.readInt32()
        val m_StructSize = reader.readInt32()

        val numVectorParams = reader.readArrayCount()
        m_VectorParams = Array(numVectorParams) { VectorParameter(reader) }

        val numMatrixParams = reader.readArrayCount()
        m_MatrixParams = Array(numMatrixParams) { MatrixParameter(reader) }
    }
}

class SamplerParameter(reader: ObjectReader) {
    val sampler: Long = reader.readUInt32()
    val bindPoint: Int = reader.readInt32()
}

enum class TextureDimension(val value: Int) {
    Unknown(-1),
    None(0),
    Any(1),
    Tex2D(2),
    Tex3D(3),
    Cube(4),
    Tex2DArray(5),
    CubeArray(6);

    companion object {
        fun fromValue(value: Int): TextureDimension =
            entries.firstOrNull { it.value == value } ?: Unknown
    }
}

class SerializedTextureProperty(reader: ObjectReader) {
    val m_DefaultName: String = reader.readAlignedString()
    val m_TexDim: TextureDimension = TextureDimension.fromValue(reader.readInt32())
}

enum class SerializedPropertyType(val value: Int) {
    Color(0),
    Vector(1),
    Float(2),
    Range(3),
    Texture(4),
    Int(5);

    companion object {
        fun fromValue(value: Int): SerializedPropertyType =
            entries.firstOrNull { it.value == value } ?: Color
    }
}

class SerializedProperty(reader: ObjectReader) {
    val m_Name: String
    val m_Description: String
    val m_Attributes: Array<String>
    val m_Type: SerializedPropertyType
    val m_Flags: Long
    val m_DefValue: FloatArray
    val m_DefTexture: SerializedTextureProperty

    init {
        m_Name = reader.readAlignedString()
        m_Description = reader.readAlignedString()
        m_Attributes = reader.readStringArray()
        m_Type = SerializedPropertyType.fromValue(reader.readInt32())
        m_Flags = reader.readUInt32()
        m_DefValue = reader.readSingleArray(4)
        m_DefTexture = SerializedTextureProperty(reader)
    }
}

class SerializedProperties(reader: ObjectReader) {
    val m_Props: Array<SerializedProperty> = Array(reader.readArrayCount()) { SerializedProperty(reader) }
}

class SerializedShaderFloatValue(reader: ObjectReader) {
    val `val`: Float = reader.readSingle()
    val name: String = reader.readAlignedString()
}

class SerializedShaderRTBlendState(reader: ObjectReader) {
    val srcBlend = SerializedShaderFloatValue(reader)
    val destBlend = SerializedShaderFloatValue(reader)
    val srcBlendAlpha = SerializedShaderFloatValue(reader)
    val destBlendAlpha = SerializedShaderFloatValue(reader)
    val blendOp = SerializedShaderFloatValue(reader)
    val blendOpAlpha = SerializedShaderFloatValue(reader)
    val colMask = SerializedShaderFloatValue(reader)
}

class SerializedStencilOp(reader: ObjectReader) {
    val pass = SerializedShaderFloatValue(reader)
    val fail = SerializedShaderFloatValue(reader)
    val zFail = SerializedShaderFloatValue(reader)
    val comp = SerializedShaderFloatValue(reader)
}

class SerializedShaderVectorValue(reader: ObjectReader) {
    val x = SerializedShaderFloatValue(reader)
    val y = SerializedShaderFloatValue(reader)
    val z = SerializedShaderFloatValue(reader)
    val w = SerializedShaderFloatValue(reader)
    val name: String = reader.readAlignedString()
}

enum class FogMode(val value: Int) {
    Unknown(-1),
    Disabled(0),
    Linear(1),
    Exp(2),
    Exp2(3);

    companion object {
        fun fromValue(value: Int): FogMode = entries.firstOrNull { it.value == value } ?: Unknown
    }
}

class SerializedShaderState(reader: ObjectReader) {
    val m_Name: String
    val rtBlend: Array<SerializedShaderRTBlendState>
    val rtSeparateBlend: Boolean
    var zClip: SerializedShaderFloatValue? = null
    val zTest: SerializedShaderFloatValue
    val zWrite: SerializedShaderFloatValue
    val culling: SerializedShaderFloatValue
    var conservative: SerializedShaderFloatValue? = null
    val offsetFactor: SerializedShaderFloatValue
    val offsetUnits: SerializedShaderFloatValue
    val alphaToMask: SerializedShaderFloatValue
    val stencilOp: SerializedStencilOp
    val stencilOpFront: SerializedStencilOp
    val stencilOpBack: SerializedStencilOp
    val stencilReadMask: SerializedShaderFloatValue
    val stencilWriteMask: SerializedShaderFloatValue
    val stencilRef: SerializedShaderFloatValue
    val fogStart: SerializedShaderFloatValue
    val fogEnd: SerializedShaderFloatValue
    val fogDensity: SerializedShaderFloatValue
    val fogColor: SerializedShaderVectorValue
    val fogMode: FogMode
    val gpuProgramID: Int
    val m_Tags: SerializedTagMap
    val m_LOD: Int
    val lighting: Boolean

    init {
        val version = reader.version

        m_Name = reader.readAlignedString()
        rtBlend = Array(8) { SerializedShaderRTBlendState(reader) }
        rtSeparateBlend = reader.readBoolean()
        reader.alignStream()
        if (version[0] > 2017 || (version[0] == 2017 && version[1] >= 2)) { //2017.2 and up
            zClip = SerializedShaderFloatValue(reader)
        }
        zTest = SerializedShaderFloatValue(reader)
        zWrite = SerializedShaderFloatValue(reader)
        culling = SerializedShaderFloatValue(reader)
        if (version[0] >= 2020) { //2020.1 and up
            conservative = SerializedShaderFloatValue(reader)
        }
        offsetFactor = SerializedShaderFloatValue(reader)
        offsetUnits = SerializedShaderFloatValue(reader)
        alphaToMask = SerializedShaderFloatValue(reader)
        stencilOp = SerializedStencilOp(reader)
        stencilOpFront = SerializedStencilOp(reader)
        stencilOpBack = SerializedStencilOp(reader)
        stencilReadMask = SerializedShaderFloatValue(reader)
        stencilWriteMask = SerializedShaderFloatValue(reader)
        stencilRef = SerializedShaderFloatValue(reader)
        fogStart = SerializedShaderFloatValue(reader)
        fogEnd = SerializedShaderFloatValue(reader)
        fogDensity = SerializedShaderFloatValue(reader)
        fogColor = SerializedShaderVectorValue(reader)
        fogMode = FogMode.fromValue(reader.readInt32())
        gpuProgramID = reader.readInt32()
        m_Tags = SerializedTagMap(reader)
        m_LOD = reader.readInt32()
        lighting = reader.readBoolean()
        reader.alignStream()
    }
}

class ShaderBindChannel(reader: ObjectReader) {
    val source: Byte = reader.readInt8()
    val target: Byte = reader.readInt8()
}

class ParserBindChannels(reader: ObjectReader) {
    val m_Channels: Array<ShaderBindChannel>
    val m_SourceMap: Long

    init {
        val numChannels = reader.readArrayCount()
        m_Channels = Array(numChannels) { ShaderBindChannel(reader) }
        reader.alignStream()

        m_SourceMap = reader.readUInt32()
    }
}

class VectorParameter(reader: ObjectReader) {
    val m_NameIndex: Int = reader.readInt32()
    val m_Index: Int = reader.readInt32()
    val m_ArraySize: Int = reader.readInt32()
    val m_Type: Byte = reader.readInt8()
    val m_Dim: Byte = reader.readInt8()

    init {
        reader.alignStream()
    }
}

class MatrixParameter(reader: ObjectReader) {
    val m_NameIndex: Int = reader.readInt32()
    val m_Index: Int = reader.readInt32()
    val m_ArraySize: Int = reader.readInt32()
    val m_Type: Byte = reader.readInt8()
    val m_RowCount: Byte = reader.readInt8()

    init {
        reader.alignStream()
    }
}

class TextureParameter(reader: ObjectReader) {
    val m_NameIndex: Int
    val m_Index: Int
    val m_SamplerIndex: Int
    val m_Dim: Byte

    init {
        val version = reader.version

        m_NameIndex = reader.readInt32()
        m_Index = reader.readInt32()
        m_SamplerIndex = reader.readInt32()
        if (version[0] > 2017 || (version[0] == 2017 && version[1] >= 3)) { //2017.3 and up
            val m_MultiSampled = reader.readBoolean()
        }
        m_Dim = reader.readInt8()
        reader.alignStream()
    }
}

class BufferBinding(reader: ObjectReader) {
    val m_NameIndex: Int
    val m_Index: Int
    var m_ArraySize: Int = 0

    init {
        val version = reader.version

        m_NameIndex = reader.readInt32()
        m_Index = reader.readInt32()
        if (version[0] >= 2020) { //2020.1 and up
            m_ArraySize = reader.readInt32()
        }
    }
}

class ConstantBuffer(reader: ObjectReader) {
    val m_NameIndex: Int
    val m_MatrixParams: Array<MatrixParameter>
    val m_VectorParams: Array<VectorParameter>
    var m_StructParams: Array<StructParameter>? = null
    val m_Size: Int
    var m_IsPartialCB: Boolean = false

    init {
        val version = reader.version

        m_NameIndex = reader.readInt32()

        val numMatrixParams = reader.readArrayCount()
        m_MatrixParams = Array(numMatrixParams) { MatrixParameter(reader) }

        val numVectorParams = reader.readArrayCount()
        m_VectorParams = Array(numVectorParams) { VectorParameter(reader) }
        if (version[0] > 2017 || (version[0] == 2017 && version[1] >= 3)) { //2017.3 and up
            val numStructParams = reader.readArrayCount()
            m_StructParams = Array(numStructParams) { StructParameter(reader) }
        }
        m_Size = reader.readInt32()

        if ((version[0] == 2020 && version[1] > 3) ||
            (version[0] == 2020 && version[1] == 3 && version[2] >= 2) || //2020.3.2f1 and up
            (version[0] > 2021) ||
            (version[0] == 2021 && version[1] > 1) ||
            (version[0] == 2021 && version[1] == 1 && version[2] >= 4)
        ) { //2021.1.4f1 and up
            m_IsPartialCB = reader.readBoolean()
            reader.alignStream()
        }
    }
}

class UAVParameter(reader: ObjectReader) {
    val m_NameIndex: Int = reader.readInt32()
    val m_Index: Int = reader.readInt32()
    val m_OriginalIndex: Int = reader.readInt32()
}

enum class ShaderGpuProgramType(val value: Int) {
    Unknown(0),
    GLLegacy(1),
    GLES31AEP(2),
    GLES31(3),
    GLES3(4),
    GLES(5),
    GLCore32(6),
    GLCore41(7),
    GLCore43(8),
    DX9VertexSM20(9),
    DX9VertexSM30(10),
    DX9PixelSM20(11),
    DX9PixelSM30(12),
    DX10Level9Vertex(13),
    DX10Level9Pixel(14),
    DX11VertexSM40(15),
    DX11VertexSM50(16),
    DX11PixelSM40(17),
    DX11PixelSM50(18),
    DX11GeometrySM40(19),
    DX11GeometrySM50(20),
    DX11HullSM50(21),
    DX11DomainSM50(22),
    MetalVS(23),
    MetalFS(24),
    SPIRV(25),
    ConsoleVS(26),
    ConsoleFS(27),
    ConsoleHS(28),
    ConsoleDS(29),
    ConsoleGS(30),
    RayTracing(31),
    PS5NGGC(32);

    companion object {
        fun fromValue(value: Int): ShaderGpuProgramType =
            entries.firstOrNull { it.value == value } ?: Unknown
    }
}

class SerializedProgramParameters(reader: ObjectReader) {
    val m_VectorParams: Array<VectorParameter>
    val m_MatrixParams: Array<MatrixParameter>
    val m_TextureParams: Array<TextureParameter>
    val m_BufferParams: Array<BufferBinding>
    val m_ConstantBuffers: Array<ConstantBuffer>
    val m_ConstantBufferBindings: Array<BufferBinding>
    val m_UAVParams: Array<UAVParameter>
    val m_Samplers: Array<SamplerParameter>

    init {
        m_VectorParams = Array(reader.readArrayCount()) { VectorParameter(reader) }
        m_MatrixParams = Array(reader.readArrayCount()) { MatrixParameter(reader) }
        m_TextureParams = Array(reader.readArrayCount()) { TextureParameter(reader) }
        m_BufferParams = Array(reader.readArrayCount()) { BufferBinding(reader) }
        m_ConstantBuffers = Array(reader.readArrayCount()) { ConstantBuffer(reader) }
        m_ConstantBufferBindings = Array(reader.readArrayCount()) { BufferBinding(reader) }
        m_UAVParams = Array(reader.readArrayCount()) { UAVParameter(reader) }
        m_Samplers = Array(reader.readArrayCount()) { SamplerParameter(reader) }
    }
}

class SerializedSubProgram(reader: ObjectReader) {
    val m_BlobIndex: Long //uint
    val m_Channels: ParserBindChannels
    var m_KeywordIndices: IntArray? = null
    val m_ShaderHardwareTier: Byte
    val m_GpuProgramType: ShaderGpuProgramType
    var m_Parameters: SerializedProgramParameters? = null
    var m_VectorParams: Array<VectorParameter>? = null
    var m_MatrixParams: Array<MatrixParameter>? = null
    var m_TextureParams: Array<TextureParameter>? = null
    var m_BufferParams: Array<BufferBinding>? = null
    var m_ConstantBuffers: Array<ConstantBuffer>? = null
    var m_ConstantBufferBindings: Array<BufferBinding>? = null
    var m_UAVParams: Array<UAVParameter>? = null
    var m_Samplers: Array<SamplerParameter>? = null

    init {
        val version = reader.version

        m_BlobIndex = reader.readUInt32()
        m_Channels = ParserBindChannels(reader)

        if ((version[0] >= 2019 && version[0] < 2021) || (version[0] == 2021 && version[1] < 2)) { //2019 ~2021.1
            val m_GlobalKeywordIndices = reader.readUInt16Array()
            reader.alignStream()
            val m_LocalKeywordIndices = reader.readUInt16Array()
            reader.alignStream()
        } else {
            m_KeywordIndices = reader.readUInt16Array()
            if (version[0] >= 2017) { //2017 and up
                reader.alignStream()
            }
        }

        m_ShaderHardwareTier = reader.readInt8()
        m_GpuProgramType = ShaderGpuProgramType.fromValue(reader.readInt8().toInt())
        reader.alignStream()

        if ((version[0] == 2020 && version[1] > 3) ||
            (version[0] == 2020 && version[1] == 3 && version[2] >= 2) || //2020.3.2f1 and up
            (version[0] > 2021) ||
            (version[0] == 2021 && version[1] > 1) ||
            (version[0] == 2021 && version[1] == 1 && version[2] >= 1)
        ) { //2021.1.1f1 and up
            m_Parameters = SerializedProgramParameters(reader)
        } else {
            m_VectorParams = Array(reader.readArrayCount()) { VectorParameter(reader) }
            m_MatrixParams = Array(reader.readArrayCount()) { MatrixParameter(reader) }
            m_TextureParams = Array(reader.readArrayCount()) { TextureParameter(reader) }
            m_BufferParams = Array(reader.readArrayCount()) { BufferBinding(reader) }
            m_ConstantBuffers = Array(reader.readArrayCount()) { ConstantBuffer(reader) }
            m_ConstantBufferBindings = Array(reader.readArrayCount()) { BufferBinding(reader) }
            m_UAVParams = Array(reader.readArrayCount()) { UAVParameter(reader) }

            if (version[0] >= 2017) { //2017 and up
                m_Samplers = Array(reader.readArrayCount()) { SamplerParameter(reader) }
            }
        }

        if (version[0] > 2017 || (version[0] == 2017 && version[1] >= 2)) { //2017.2 and up
            if (version[0] >= 2021) { //2021.1 and up
                val m_ShaderRequirements = reader.readInt64()
            } else {
                val m_ShaderRequirements = reader.readInt32()
            }
        }
    }
}

class SerializedProgram(reader: ObjectReader) {
    val m_SubPrograms: Array<SerializedSubProgram>
    var m_CommonParameters: SerializedProgramParameters? = null
    var m_SerializedKeywordStateMask: IntArray? = null

    init {
        val version = reader.version

        m_SubPrograms = Array(reader.readArrayCount()) { SerializedSubProgram(reader) }

        if ((version[0] == 2020 && version[1] > 3) ||
            (version[0] == 2020 && version[1] == 3 && version[2] >= 2) || //2020.3.2f1 and up
            (version[0] > 2021) ||
            (version[0] == 2021 && version[1] > 1) ||
            (version[0] == 2021 && version[1] == 1 && version[2] >= 1)
        ) { //2021.1.1f1 and up
            m_CommonParameters = SerializedProgramParameters(reader)
        }

        if (version[0] > 2022 || (version[0] == 2022 && version[1] >= 1)) { //2022.1 and up
            m_SerializedKeywordStateMask = reader.readUInt16Array()
            reader.alignStream()
        }
    }
}

enum class PassType(val value: Int) {
    Normal(0),
    Use(1),
    Grab(2);

    companion object {
        fun fromValue(value: Int): PassType = entries.firstOrNull { it.value == value } ?: Normal
    }
}

class SerializedPass(reader: ObjectReader) {
    var m_EditorDataHash: Array<Hash128>? = null
    var m_Platforms: ByteArray? = null
    var m_LocalKeywordMask: IntArray? = null
    var m_GlobalKeywordMask: IntArray? = null
    val m_NameIndices: Array<Pair<String, Int>>
    val m_Type: PassType
    val m_State: SerializedShaderState
    val m_ProgramMask: Long //uint
    val progVertex: SerializedProgram
    val progFragment: SerializedProgram
    val progGeometry: SerializedProgram
    val progHull: SerializedProgram
    val progDomain: SerializedProgram
    var progRayTracing: SerializedProgram? = null
    val m_HasInstancingVariant: Boolean
    val m_UseName: String
    val m_Name: String
    val m_TextureName: String
    val m_Tags: SerializedTagMap
    var m_SerializedKeywordStateMask: IntArray? = null

    init {
        val version = reader.version

        if (version[0] > 2020 || (version[0] == 2020 && version[1] >= 2)) { //2020.2 and up
            val numEditorDataHash = reader.readArrayCount()
            m_EditorDataHash = Array(numEditorDataHash) { Hash128(reader) }
            reader.alignStream()
            m_Platforms = reader.readUInt8Array()
            reader.alignStream()
            if (version[0] < 2021 || (version[0] == 2021 && version[1] < 2)) { //2021.1 and down
                m_LocalKeywordMask = reader.readUInt16Array()
                reader.alignStream()
                m_GlobalKeywordMask = reader.readUInt16Array()
                reader.alignStream()
            }
        }

        val numIndices = reader.readArrayCount()
        m_NameIndices = Array(numIndices) { Pair(reader.readAlignedString(), reader.readInt32()) }

        m_Type = PassType.fromValue(reader.readInt32())
        m_State = SerializedShaderState(reader)
        m_ProgramMask = reader.readUInt32()
        progVertex = SerializedProgram(reader)
        progFragment = SerializedProgram(reader)
        progGeometry = SerializedProgram(reader)
        progHull = SerializedProgram(reader)
        progDomain = SerializedProgram(reader)
        if (version[0] > 2019 || (version[0] == 2019 && version[1] >= 3)) { //2019.3 and up
            progRayTracing = SerializedProgram(reader)
        }
        m_HasInstancingVariant = reader.readBoolean()
        if (version[0] >= 2018) { //2018 and up
            val m_HasProceduralInstancingVariant = reader.readBoolean()
        }
        reader.alignStream()
        m_UseName = reader.readAlignedString()
        m_Name = reader.readAlignedString()
        m_TextureName = reader.readAlignedString()
        m_Tags = SerializedTagMap(reader)
        if (version[0] == 2021 && version[1] >= 2) { //2021.2 ~2021.x
            m_SerializedKeywordStateMask = reader.readUInt16Array()
            reader.alignStream()
        }
    }
}

class SerializedTagMap(reader: ObjectReader) {
    val tags: Array<Pair<String, String>> = Array(reader.readArrayCount()) {
        Pair(reader.readAlignedString(), reader.readAlignedString())
    }
}

class SerializedSubShader(reader: ObjectReader) {
    val m_Passes: Array<SerializedPass> = Array(reader.readArrayCount()) { SerializedPass(reader) }
    val m_Tags: SerializedTagMap = SerializedTagMap(reader)
    val m_LOD: Int = reader.readInt32()
}

class SerializedShaderDependency(reader: ObjectReader) {
    val from: String = reader.readAlignedString()
    val to: String = reader.readAlignedString()
}

class SerializedCustomEditorForRenderPipeline(reader: ObjectReader) {
    val customEditorName: String = reader.readAlignedString()
    val renderPipelineType: String = reader.readAlignedString()
}

class SerializedShader(reader: ObjectReader) {
    val m_PropInfo: SerializedProperties
    val m_SubShaders: Array<SerializedSubShader>
    var m_KeywordNames: Array<String>? = null
    var m_KeywordFlags: ByteArray? = null
    val m_Name: String
    var m_CustomEditorName: String
    val m_FallbackName: String
    val m_Dependencies: Array<SerializedShaderDependency>
    var m_CustomEditorForRenderPipelines: Array<SerializedCustomEditorForRenderPipeline>? = null
    val m_DisableNoSubshadersMessage: Boolean

    init {
        val version = reader.version

        m_PropInfo = SerializedProperties(reader)

        m_SubShaders = Array(reader.readArrayCount()) { SerializedSubShader(reader) }

        if (version[0] > 2021 || (version[0] == 2021 && version[1] >= 2)) { //2021.2 and up
            m_KeywordNames = reader.readStringArray()
            m_KeywordFlags = reader.readUInt8Array()
            reader.alignStream()
        }

        m_Name = reader.readAlignedString()
        m_CustomEditorName = reader.readAlignedString()
        m_FallbackName = reader.readAlignedString()

        m_Dependencies = Array(reader.readArrayCount()) { SerializedShaderDependency(reader) }

        if (version[0] >= 2021) { //2021.1 and up
            m_CustomEditorForRenderPipelines =
                Array(reader.readArrayCount()) { SerializedCustomEditorForRenderPipeline(reader) }
        }

        m_DisableNoSubshadersMessage = reader.readBoolean()
        reader.alignStream()
    }
}

enum class ShaderCompilerPlatform(val value: Int) {
    None(-1),
    GL(0),
    D3D9(1),
    Xbox360(2),
    PS3(3),
    D3D11(4),
    GLES20(5),
    NaCl(6),
    Flash(7),
    D3D11_9x(8),
    GLES3Plus(9),
    PSP2(10),
    PS4(11),
    XboxOne(12),
    PSM(13),
    Metal(14),
    OpenGLCore(15),
    N3DS(16),
    WiiU(17),
    Vulkan(18),
    Switch(19),
    XboxOneD3D12(20),
    GameCoreXboxOne(21),
    GameCoreScarlett(22),
    PS5(23),
    PS5NGGC(24);

    companion object {
        fun fromValue(value: Int): ShaderCompilerPlatform =
            entries.firstOrNull { it.value == value } ?: None
    }
}

/** 读 uint32 数组的数组（长度前缀 int32） */
private fun readUInt32ArrayArray(reader: ObjectReader): Array<LongArray> =
    Array(reader.readArrayCount()) { reader.readUInt32Array() }

class Shader(reader: ObjectReader) : NamedObject(reader) {
    var m_Script: ByteArray? = null
    //5.3 - 5.4
    var decompressedSize: Long = 0 //uint
    var m_SubProgramBlob: ByteArray? = null
    //5.5 and up
    var m_ParsedForm: SerializedShader? = null
    var platforms: Array<ShaderCompilerPlatform> = emptyArray()
    var offsets: Array<LongArray> = emptyArray()
    var compressedLengths: Array<LongArray> = emptyArray()
    var decompressedLengths: Array<LongArray> = emptyArray()
    var compressedBlob: ByteArray? = null

    init {
        if (version[0] == 5 && version[1] >= 5 || version[0] > 5) { //5.5 and up
            m_ParsedForm = SerializedShader(reader)
            platforms = reader.readUInt32Array().map { ShaderCompilerPlatform.fromValue(it.toInt()) }
                .toTypedArray()
            if (version[0] > 2019 || (version[0] == 2019 && version[1] >= 3)) { //2019.3 and up
                offsets = readUInt32ArrayArray(reader)
                compressedLengths = readUInt32ArrayArray(reader)
                decompressedLengths = readUInt32ArrayArray(reader)
            } else {
                offsets = reader.readUInt32Array().map { longArrayOf(it) }.toTypedArray()
                compressedLengths = reader.readUInt32Array().map { longArrayOf(it) }.toTypedArray()
                decompressedLengths = reader.readUInt32Array().map { longArrayOf(it) }.toTypedArray()
            }
            compressedBlob = reader.readUInt8Array()
            reader.alignStream()

            val m_DependenciesCount = reader.readInt32()
            for (i in 0 until m_DependenciesCount) {
                val dependency = PPtr(reader)
            }

            if (version[0] >= 2018) {
                val m_NonModifiableTexturesCount = reader.readInt32()
                for (i in 0 until m_NonModifiableTexturesCount) {
                    val first = reader.readAlignedString()
                    val texture = PPtr(reader)
                }
            }

            val m_ShaderIsBaked = reader.readBoolean()
            reader.alignStream()
        } else {
            m_Script = reader.readUInt8Array()
            reader.alignStream()
            val m_PathName = reader.readAlignedString()
            if (version[0] == 5 && version[1] >= 3) { //5.3 - 5.4
                decompressedSize = reader.readUInt32()
                m_SubProgramBlob = reader.readUInt8Array()
            }
        }
    }
}

package com.assetstudio.mobile.core.classes

/*
 * 来源: AssetStudio/Classes/Sprite.cs
 * 辅助方法（getTexture/getRenderData/getRect 等）参照 AssetStudioGUI/SpriteHelper.cs 的使用方式。
 */

import com.assetstudio.mobile.core.ObjectReader
import com.assetstudio.mobile.core.math.Vector2
import com.assetstudio.mobile.core.math.Vector3
import com.assetstudio.mobile.core.math.Vector4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class SecondarySpriteTexture(reader: ObjectReader) {
    val texture: PPtr = PPtr(reader) // PPtr<Texture2D>
    val name: String = reader.readStringToNull()
}

enum class SpritePackingRotation(val value: Int) {
    None(0),
    FlipHorizontal(1),
    FlipVertical(2),
    Rotate180(3),
    Rotate90(4);

    companion object {
        fun fromValue(value: Int): SpritePackingRotation =
            entries.firstOrNull { it.value == value } ?: None
    }
}

enum class SpritePackingMode(val value: Int) {
    Tight(0),
    Rectangle(1);

    companion object {
        fun fromValue(value: Int): SpritePackingMode =
            entries.firstOrNull { it.value == value } ?: Tight
    }
}

enum class SpriteMeshType(val value: Int) {
    FullRect(0),
    Tight(1);

    companion object {
        fun fromValue(value: Int): SpriteMeshType =
            entries.firstOrNull { it.value == value } ?: FullRect
    }
}

class SpriteSettings(reader: ObjectReader) {
    val settingsRaw: Long //uint

    val packed: Int
    val packingMode: SpritePackingMode
    val packingRotation: SpritePackingRotation
    val meshType: SpriteMeshType

    init {
        settingsRaw = reader.readUInt32()

        packed = (settingsRaw and 1L).toInt() //1
        packingMode = SpritePackingMode.fromValue(((settingsRaw shr 1) and 1L).toInt()) //1
        packingRotation = SpritePackingRotation.fromValue(((settingsRaw shr 2) and 0xF).toInt()) //4
        meshType = SpriteMeshType.fromValue(((settingsRaw shr 6) and 1L).toInt()) //1
        //reserved
    }
}

class SpriteVertex(reader: ObjectReader) {
    val pos: Vector3 = reader.readVector3()
    var uv: Vector2? = null

    init {
        if (reader.version[0] < 4 || (reader.version[0] == 4 && reader.version[1] <= 3)) { //4.3 and down
            uv = reader.readVector2()
        }
    }
}

class SpriteRenderData(reader: ObjectReader) {
    val texture: PPtr // PPtr<Texture2D>
    val alphaTexture: PPtr? // PPtr<Texture2D>
    val secondaryTextures: Array<SecondarySpriteTexture>?
    val m_SubMeshes: Array<SubMesh>?
    val m_IndexBuffer: ByteArray?
    val m_VertexData: VertexData?
    val vertices: Array<SpriteVertex>?
    val indices: IntArray?
    var m_Bindpose: Array<com.assetstudio.mobile.core.math.Matrix4x4>? = null
    var m_SourceSkin: Array<BoneWeights4>? = null
    val textureRect: Rectf
    val textureRectOffset: Vector2
    var atlasRectOffset: Vector2? = null
    val settingsRaw: SpriteSettings
    var uvTransform: Vector4? = null
    var downscaleMultiplier: Float = 0f

    init {
        val version = reader.version

        texture = PPtr(reader)
        alphaTexture = if (version[0] > 5 || (version[0] == 5 && version[1] >= 2)) { //5.2 and up
            PPtr(reader)
        } else {
            null
        }

        if (version[0] >= 2019) { //2019 and up
            val secondaryTexturesSize = reader.readArrayCount()
            secondaryTextures = Array(secondaryTexturesSize) { SecondarySpriteTexture(reader) }
        } else {
            secondaryTextures = null
        }

        if (version[0] > 5 || (version[0] == 5 && version[1] >= 6)) { //5.6 and up
            val m_SubMeshesSize = reader.readArrayCount()
            m_SubMeshes = Array(m_SubMeshesSize) { SubMesh(reader) }

            m_IndexBuffer = reader.readUInt8Array()
            reader.alignStream()

            m_VertexData = VertexData(reader)
            vertices = null
            indices = null
        } else {
            m_SubMeshes = null
            m_IndexBuffer = null
            m_VertexData = null
            val verticesSize = reader.readArrayCount()
            vertices = Array(verticesSize) { SpriteVertex(reader) }

            indices = reader.readUInt16Array()
            reader.alignStream()
        }

        if (version[0] >= 2018) { //2018 and up
            m_Bindpose = reader.readMatrixArray()

            if (version[0] == 2018 && version[1] < 2) { //2018.2 down
                val m_SourceSkinSize = reader.readArrayCount()
                m_SourceSkin = Array(m_SourceSkinSize) { BoneWeights4(reader) }
            }
        }

        textureRect = Rectf(reader)
        textureRectOffset = reader.readVector2()
        if (version[0] > 5 || (version[0] == 5 && version[1] >= 6)) { //5.6 and up
            atlasRectOffset = reader.readVector2()
        }

        settingsRaw = SpriteSettings(reader)
        if (version[0] > 4 || (version[0] == 4 && version[1] >= 5)) { //4.5 and up
            uvTransform = reader.readVector4()
        }

        if (version[0] >= 2017) { //2017 and up
            downscaleMultiplier = reader.readSingle()
        }
    }
}

data class Rectf(
    var x: Float = 0f,
    var y: Float = 0f,
    var width: Float = 0f,
    var height: Float = 0f
) {
    constructor(reader: ObjectReader) : this(
        reader.readSingle(),
        reader.readSingle(),
        reader.readSingle(),
        reader.readSingle()
    )

    val right: Float get() = x + width
    val bottom: Float get() = y + height
}

/** C# KeyValuePair<Guid, long>，键值相等性用于 SpriteAtlas.m_RenderDataMap 查找 */
class RenderDataKey(val guid: UUID, val value: Long) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RenderDataKey) return false
        return guid == other.guid && value == other.value
    }

    override fun hashCode(): Int = 31 * guid.hashCode() + value.hashCode()

    override fun toString(): String = "$guid-$value"

    companion object {
        /** 读取 16 字节 Guid + 8 字节 long（字节序与 C# new Guid(byte[]) 一致） */
        fun read(reader: ObjectReader): RenderDataKey {
            val bytes = reader.readBytes(16)
            return RenderDataKey(guidFromBytes(bytes), reader.readInt64())
        }

        /**
         * .NET Guid 字节布局：前 4 字节 int32(LE) + 2 字节 int16(LE) + 2 字节 int16(LE) + 8 字节(BE)。
         * 此处保证 Sprite 与 SpriteAtlas 使用同一转换函数即可正确匹配键。
         */
        private fun guidFromBytes(bytes: ByteArray): UUID {
            val msb: Long
            var lsb = 0L
            if (bytes.size >= 16) {
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val a = bb.int.toLong() and 0xFFFFFFFFL
                val b = bb.short.toLong() and 0xFFFFL
                val c = bb.short.toLong() and 0xFFFFL
                msb = (a shl 32) or (b shl 16) or c
                for (i in 8 until 16) {
                    lsb = (lsb shl 8) or (bytes[i].toLong() and 0xFF)
                }
            } else {
                msb = 0L
            }
            return UUID(msb, lsb)
        }
    }
}

class Sprite(reader: ObjectReader) : NamedObject(reader) {
    val m_Rect: Rectf
    val m_Offset: Vector2
    val m_Border: Vector4?
    val m_PixelsToUnits: Float
    var m_Pivot: Vector2 = Vector2(0.5f, 0.5f)
    val m_Extrude: Long
    val m_IsPolygon: Boolean
    val m_RenderDataKey: RenderDataKey?
    val m_AtlasTags: Array<String>?
    val m_SpriteAtlas: PPtr?
    val m_RD: SpriteRenderData
    val m_PhysicsShape: Array<Array<Vector2>>?

    init {
        m_Rect = Rectf(reader)
        m_Offset = reader.readVector2()
        if (version[0] > 4 || (version[0] == 4 && version[1] >= 5)) { //4.5 and up
            m_Border = reader.readVector4()
        } else {
            m_Border = null
        }

        m_PixelsToUnits = reader.readSingle()
        if (version[0] > 5
            || (version[0] == 5 && version[1] > 4)
            || (version[0] == 5 && version[1] == 4 && version[2] >= 2)
            || (version[0] == 5 && version[1] == 4 && version[2] == 1 && buildType?.isPatch == true && version[3] >= 3)
        ) { //5.4.1p3 and up
            m_Pivot = reader.readVector2()
        }

        m_Extrude = reader.readUInt32()
        if (version[0] > 5 || (version[0] == 5 && version[1] >= 3)) { //5.3 and up
            m_IsPolygon = reader.readBoolean()
            reader.alignStream()
        } else {
            m_IsPolygon = false
        }

        if (version[0] >= 2017) { //2017 and up
            m_RenderDataKey = RenderDataKey.read(reader)
            m_AtlasTags = reader.readStringArray()
            m_SpriteAtlas = PPtr(reader)
        } else {
            m_RenderDataKey = null
            m_AtlasTags = null
            m_SpriteAtlas = null
        }

        m_RD = SpriteRenderData(reader)

        if (version[0] >= 2017) { //2017 and up
            val m_PhysicsShapeSize = reader.readArrayCount()
            m_PhysicsShape = Array(m_PhysicsShapeSize) { reader.readVector2Array() }
        } else {
            m_PhysicsShape = null
        }

        //vector m_Bones 2018 and up
    }

    /** Sprite 的渲染矩形（原始 rect，单位像素） */
    fun getRect(): Rectf = m_Rect

    /**
     * Sprite 在图集（或独立纹理）上的裁剪矩形 m_AtlasRect：
     * 打包进 SpriteAtlas 时取 SpriteAtlasData.textureRect，否则取 m_RD.textureRect。
     * 与 SpriteHelper.CutImage 使用的 textureRect 是同一个量。
     */
    fun getAtlasRect(): Rectf? {
        val atlasPtr = m_SpriteAtlas
        if (atlasPtr != null) {
            val spriteAtlas: SpriteAtlas? = atlasPtr.tryGet()
            val data = spriteAtlas?.findRenderData(m_RenderDataKey)
            if (data != null) {
                return data.textureRect
            }
            return null
        }
        return m_RD.textureRect
    }

    /**
     * 获取 Sprite 使用的纹理：
     * 优先走 SpriteAtlas（m_RenderDataKey 查找），否则使用 m_RD.texture。
     * 逻辑与 AssetStudioGUI SpriteHelper.GetImage 一致。
     */
    fun getTexture(): Texture2D? {
        val atlasPtr = m_SpriteAtlas
        if (atlasPtr != null) {
            val spriteAtlas: SpriteAtlas? = atlasPtr.tryGet()
            if (spriteAtlas != null) {
                val data = spriteAtlas.findRenderData(m_RenderDataKey)
                if (data != null) {
                    return data.texture.tryGet()
                }
                return null
            }
        }
        return m_RD.texture.tryGet()
    }

    /** 获取 Sprite 的 Alpha 纹理（可能在 RD 或 Atlas 数据中） */
    fun getAlphaTexture(): Texture2D? {
        val atlasPtr = m_SpriteAtlas
        if (atlasPtr != null) {
            val spriteAtlas: SpriteAtlas? = atlasPtr.tryGet()
            if (spriteAtlas != null) {
                val data = spriteAtlas.findRenderData(m_RenderDataKey)
                if (data != null) {
                    return data.alphaTexture.tryGet()
                }
                return null
            }
        }
        return m_RD.alphaTexture?.tryGet()
    }

    /** Sprite 在图集/纹理上的裁剪信息（等价 SpriteHelper.CutImage 的参数集合） */
    data class SpriteTextureInfo(
        val texture: Texture2D,
        val textureRect: Rectf,
        val textureRectOffset: Vector2,
        val downscaleMultiplier: Float,
        val settingsRaw: SpriteSettings
    )

    fun getTextureInfo(): SpriteTextureInfo? {
        val atlasPtr = m_SpriteAtlas
        if (atlasPtr != null) {
            val spriteAtlas: SpriteAtlas? = atlasPtr.tryGet()
            if (spriteAtlas != null) {
                val data = spriteAtlas.findRenderData(m_RenderDataKey)
                if (data != null) {
                    val texture: Texture2D? = data.texture.tryGet()
                    if (texture != null) {
                        return SpriteTextureInfo(
                            texture, data.textureRect, data.textureRectOffset,
                            data.downscaleMultiplier, data.settingsRaw
                        )
                    }
                }
                return null
            }
        }
        val texture: Texture2D? = m_RD.texture.tryGet()
        if (texture != null) {
            return SpriteTextureInfo(
                texture, m_RD.textureRect, m_RD.textureRectOffset,
                m_RD.downscaleMultiplier, m_RD.settingsRaw
            )
        }
        return null
    }
}

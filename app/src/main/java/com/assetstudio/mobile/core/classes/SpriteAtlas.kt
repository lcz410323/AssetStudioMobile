package com.assetstudio.mobile.core.classes

/*
 * 来源: AssetStudio/Classes/SpriteAtlas.cs
 */

import com.assetstudio.mobile.core.ObjectReader
import com.assetstudio.mobile.core.math.Vector2
import com.assetstudio.mobile.core.math.Vector4

class SpriteAtlasData(reader: ObjectReader) {
    val texture: PPtr // PPtr<Texture2D>
    val alphaTexture: PPtr // PPtr<Texture2D>
    val textureRect: Rectf
    val textureRectOffset: Vector2
    var atlasRectOffset: Vector2? = null
    val uvTransform: Vector4
    val downscaleMultiplier: Float
    val settingsRaw: SpriteSettings
    val secondaryTextures: Array<SecondarySpriteTexture>?

    init {
        val version = reader.version
        texture = PPtr(reader)
        alphaTexture = PPtr(reader)
        textureRect = Rectf(reader)
        textureRectOffset = reader.readVector2()
        if (version[0] > 2017 || (version[0] == 2017 && version[1] >= 2)) { //2017.2 and up
            atlasRectOffset = reader.readVector2()
        }
        uvTransform = reader.readVector4()
        downscaleMultiplier = reader.readSingle()
        settingsRaw = SpriteSettings(reader)
        if (version[0] > 2020 || (version[0] == 2020 && version[1] >= 2)) { //2020.2 and up
            val secondaryTexturesSize = reader.readArrayCount()
            secondaryTextures = Array(secondaryTexturesSize) { SecondarySpriteTexture(reader) }
            reader.alignStream()
        } else {
            secondaryTextures = null
        }
    }
}

class SpriteAtlas(reader: ObjectReader) : NamedObject(reader) {
    val m_PackedSprites: Array<PPtr> // PPtr<Sprite>[]
    val m_RenderDataMap: Map<RenderDataKey, SpriteAtlasData>
    val m_IsVariant: Boolean

    init {
        val m_PackedSpritesSize = reader.readArrayCount()
        m_PackedSprites = Array(m_PackedSpritesSize) { PPtr(reader) }

        val m_PackedSpriteNamesToIndex = reader.readStringArray()

        val m_RenderDataMapSize = reader.readInt32()
        val map = LinkedHashMap<RenderDataKey, SpriteAtlasData>(m_RenderDataMapSize)
        for (i in 0 until m_RenderDataMapSize) {
            val key = RenderDataKey.read(reader)
            val value = SpriteAtlasData(reader)
            map[key] = value
        }
        m_RenderDataMap = map
        val m_Tag = reader.readAlignedString()
        m_IsVariant = reader.readBoolean()
        reader.alignStream()
    }

    fun findRenderData(key: RenderDataKey?): SpriteAtlasData? {
        if (key == null) return null
        return m_RenderDataMap[key]
    }

    /** 遍历查找指定 Sprite 对应的图集数据 */
    fun findRenderData(sprite: Sprite): SpriteAtlasData? = findRenderData(sprite.m_RenderDataKey)
}

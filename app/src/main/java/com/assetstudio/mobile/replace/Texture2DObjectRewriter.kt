package com.assetstudio.mobile.replace

import com.assetstudio.mobile.core.ObjectReader
import com.assetstudio.mobile.core.classes.Texture2D
import com.assetstudio.mobile.core.classes.TextureFormat
import com.assetstudio.mobile.core.io.EndianType

/*
 * Texture2D 对象字节重写器。
 *
 * 原理：Texture2D 对象的序列化布局为
 *   [头部字段（宽度/高度/格式/设置等）][int32 图像数据大小][可选 StreamingInfo][图像数据]
 * 其中 StreamingInfo（外部 .resS 流数据）仅在图像数据大小为 0 时序列化
 * （与 AssetStudio/UABE 解析器的条件逻辑一致）。
 *
 * 替换策略：
 * - 保留头部字段原始字节（名称、设置、m_SpriteAtlas 等 PPtr 一律不动），
 *   仅原地修补 width/height/format/mipCount/completeImageSize 几个 int 字段；
 * - 图像数据改为内联写入（dataSize > 0），此时按上述条件语义不再序列化
 *   StreamingInfo，因此原本走 .resS 流数据的贴图也能替换为内联数据；
 * - 对象大小变化由 SerializedFileRewriter 负责整体重排。
 */
class Texture2DObjectRewriter(private val texture: Texture2D) {

    /** 对象数据使用的字节序（跟随所属 SerializedFile） */
    private val endian: EndianType = texture.reader.endian

    // 关键字段的绝对偏移（相对 SerializedFile 字节缓冲）
    private var widthOffset = -1
    private var heightOffset = -1
    private var completeImageSizeOffset = -1
    private var formatOffset = -1
    private var mipCountOffset = -1
    private var mipMapBoolOffset = -1
    private var dataSizeOffset = -1

    /** 原对象起始绝对偏移 */
    val objectStart: Int = texture.reader.byteStart.toInt()

    init {
        walkLayout()
    }

    /**
     * 重新走一遍 Texture2D 解析流程，记录需要修补的字段偏移。
     * 条件分支必须与 classes/Texture2D.kt 保持一致。
     */
    private fun walkLayout() {
        val reader = ObjectReader(
            texture.reader.fileBuffer,
            texture.assetsFile,
            findObjectInfo() ?: throw IllegalStateException("ObjectInfo not found for Texture2D ${texture.m_PathID}")
        )
        val version = texture.version

        // ---- 父类继承链（与 Texture2D 解析器的构造顺序一致）----
        // EditorExtension：platform == NoTarget 时两个 PPtr（m_PrefabParentObject / m_PrefabInternal）
        if (reader.platform == com.assetstudio.mobile.core.io.BuildTarget.NoTarget) {
            com.assetstudio.mobile.core.classes.PPtr(reader)
            com.assetstudio.mobile.core.classes.PPtr(reader)
        }
        // NamedObject：m_Name 对齐字符串
        reader.readAlignedString()
        // Texture：2017.3+ fallback 块
        if (version[0] > 2017 || (version[0] == 2017 && version[1] >= 3)) {
            reader.readInt32()             // m_ForcedFallbackFormat
            reader.readBoolean()           // m_DownscaleFallback
            if (version[0] > 2020 || (version[0] == 2020 && version[1] >= 2)) {
                reader.readBoolean()       // m_IsAlphaChannelOptional（2020.2+）
            }
            reader.alignStream()
        }

        // ---- Texture2D 自身字段 ----
        widthOffset = reader.position
        reader.readInt32()
        heightOffset = reader.position
        reader.readInt32()
        completeImageSizeOffset = reader.position
        reader.readInt32()
        if (version[0] >= 2020) reader.readInt32() // m_MipsStripped
        formatOffset = reader.position
        reader.readInt32()
        if (version[0] < 5 || (version[0] == 5 && version[1] < 2)) {
            mipMapBoolOffset = reader.position
            reader.readBoolean()
        } else {
            mipCountOffset = reader.position
            reader.readInt32()
        }
        if (version[0] > 2 || (version[0] == 2 && version[1] >= 6)) reader.readBoolean() // m_IsReadable
        if (version[0] >= 2020) reader.readBoolean() // m_IsPreProcessed
        if (version[0] > 2019 || (version[0] == 2019 && version[1] >= 3)) reader.readBoolean()
        if (version[0] >= 3 && (version[0] < 5 || (version[0] == 5 && version[1] <= 4))) reader.readBoolean()
        if (version[0] > 2018 || (version[0] == 2018 && version[1] >= 2)) reader.readBoolean() // m_StreamingMipmaps
        reader.alignStream()
        if (version[0] > 2018 || (version[0] == 2018 && version[1] >= 2)) reader.readInt32() // priority
        reader.readInt32() // m_ImageCount
        reader.readInt32() // m_TextureDimension
        // GLTextureSettings
        reader.readInt32(); reader.readInt32(); reader.readSingle()
        if (version[0] >= 2017) {
            reader.readInt32(); reader.readInt32(); reader.readInt32()
        } else {
            reader.readInt32()
        }
        if (version[0] >= 3) reader.readInt32() // m_LightmapFormat
        if (version[0] > 3 || (version[0] == 3 && version[1] >= 5)) reader.readInt32() // m_ColorSpace
        if (version[0] > 2020 || (version[0] == 2020 && version[1] >= 2)) {
            reader.readUInt8Array()
            reader.alignStream()
        }
        dataSizeOffset = reader.position
    }

    private fun findObjectInfo() =
        texture.assetsFile.m_Objects.firstOrNull { it.m_PathID == texture.m_PathID }

    /**
     * 生成替换后的完整对象字节。
     *
     * @param newWidth 新宽度
     * @param newHeight 新高度
     * @param newFormat 新格式（可能与原格式不同，字段同步改写）
     * @param newMipCount 新 mip 级数
     * @param newData 全部 mip 串联的编码数据
     */
    fun buildReplacement(
        newWidth: Int,
        newHeight: Int,
        newFormat: TextureFormat,
        newMipCount: Int,
        newData: ByteArray
    ): ByteArray {
        val original = texture.reader.fileBuffer
        val objStart = objectStart

        // 头部 = [objStart, dataSizeOffset)，修补字段后原样保留
        val headerLen = dataSizeOffset - objStart
        val out = ByteArray(headerLen + 4 + newData.size)
        System.arraycopy(original, objStart, out, 0, headerLen)

        fun patchInt32(absOffset: Int, value: Int) {
            val rel = absOffset - objStart
            if (rel < 0 || rel + 4 > headerLen) return
            if (endian == EndianType.LittleEndian) {
                out[rel] = (value and 0xFF).toByte()
                out[rel + 1] = (value shr 8 and 0xFF).toByte()
                out[rel + 2] = (value shr 16 and 0xFF).toByte()
                out[rel + 3] = (value shr 24 and 0xFF).toByte()
            } else {
                out[rel] = (value shr 24 and 0xFF).toByte()
                out[rel + 1] = (value shr 16 and 0xFF).toByte()
                out[rel + 2] = (value shr 8 and 0xFF).toByte()
                out[rel + 3] = (value and 0xFF).toByte()
            }
        }

        patchInt32(widthOffset, newWidth)
        patchInt32(heightOffset, newHeight)
        patchInt32(completeImageSizeOffset, newData.size)
        patchInt32(formatOffset, newFormat.value)
        if (mipCountOffset >= 0) patchInt32(mipCountOffset, newMipCount)

        // 图像数据大小 + 数据本体
        var o = headerLen
        if (endian == EndianType.LittleEndian) {
            out[o++] = (newData.size and 0xFF).toByte()
            out[o++] = (newData.size shr 8 and 0xFF).toByte()
            out[o++] = (newData.size shr 16 and 0xFF).toByte()
            out[o++] = (newData.size shr 24 and 0xFF).toByte()
        } else {
            out[o++] = (newData.size shr 24 and 0xFF).toByte()
            out[o++] = (newData.size shr 16 and 0xFF).toByte()
            out[o++] = (newData.size shr 8 and 0xFF).toByte()
            out[o++] = (newData.size and 0xFF).toByte()
        }
        System.arraycopy(newData, 0, out, o, newData.size)
        return out
    }
}

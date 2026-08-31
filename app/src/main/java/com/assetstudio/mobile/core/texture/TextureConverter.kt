package com.assetstudio.mobile.core.texture

/*
 * 来源: AssetStudio/AssetStudioUtility/Texture2DConverter.cs
 *       + Texture2DDecoderWrapper/TextureDecoder.cs（P/Invoke 层，Kotlin 端替换为 NativeDecoder JNI）
 *
 * 输出像素格式: IntArray（长度 w*h），每元素 0xAARRGGBB
 * （C# 版输出为 BGRA32 字节序，此处统一转为 Android 使用的 ARGB_8888 int 序）
 *
 * 纯 Kotlin 实现: Alpha8/ARGB4444/RGB24/RGBA32/ARGB32/ARGBFloat/RGB565/BGR24/R16/
 *   RHalf/RGHalf/RGBAHalf/RFloat/RGFloat/RGBAFloat/RGBA4444/BGRA32/YUY2/RGB9e5Float/
 *   RG16/R8/RG32/RGB48/RGBA64/RGBFloat
 * JNI(NativeDecoder): DXT1(BC1)/DXT5(BC3)/BC4/BC5/BC6H/BC7/PVRTC/ETC1/ETC2/EAC/ASTC/ATC/Crunch
 * 未实现: DXT3（C# 中同样未实现）
 */

import com.assetstudio.mobile.core.ResourceReader
import com.assetstudio.mobile.core.classes.Texture2D
import com.assetstudio.mobile.core.classes.TextureFormat
import com.assetstudio.mobile.core.io.BuildTarget
import com.assetstudio.mobile.core.math.Half

class TextureConverter(texture: Texture2D) {

    private val reader: ResourceReader = texture.imageData
    private val m_Width: Int = texture.m_Width
    private val m_Height: Int = texture.m_Height
    private val m_TextureFormat: TextureFormat = texture.m_TextureFormat
    private val version: IntArray = texture.version
    private val platform: BuildTarget = texture.platform
    private val outPutSize: Int = m_Width * m_Height * 4

    /** 最近一次失败原因（成功时为 null） */
    var lastError: String? = null
        private set

    /**
     * 解码为 w*h 的 0xAARRGGBB 像素数组；失败返回 null 并设置 [lastError]。
     */
    fun decodeToPixels(): IntArray? {
        lastError = null
        if (reader.size == 0L) {
            lastError = "Texture data size is 0"
            return null
        }
        if (m_Width <= 0 || m_Height <= 0) {
            lastError = "Invalid texture size ${m_Width}x${m_Height}"
            return null
        }

        val imageData = try {
            reader.getData()
        } catch (e: Exception) {
            lastError = "Failed to read texture data: ${e.message}"
            return null
        }

        val image = IntArray(m_Width * m_Height)
        return try {
            if (decodeTexture2D(imageData, image)) {
                image
            } else {
                if (lastError == null) {
                    lastError = "Unsupported or failed texture format: $m_TextureFormat"
                }
                null
            }
        } catch (e: Throwable) {
            lastError = "Decode $m_TextureFormat failed: ${e.message}"
            null
        }
    }

    /** 对应 C# DecodeTexture2D(byte[] bytes) */
    fun decodeTexture2D(imageData: ByteArray, image: IntArray): Boolean {
        if (reader.size == 0L || m_Width == 0 || m_Height == 0) {
            return false
        }
        var flag = false
        val buff = imageData.copyOf()
        swapBytesForXbox(buff)
        when (m_TextureFormat) {
            TextureFormat.Alpha8 -> flag = decodeAlpha8(buff, image) //test pass
            TextureFormat.ARGB4444 -> flag = decodeARGB4444(buff, image) //test pass
            TextureFormat.RGB24 -> flag = decodeRGB24(buff, image) //test pass
            TextureFormat.RGBA32 -> flag = decodeRGBA32(buff, image) //test pass
            TextureFormat.ARGB32 -> flag = decodeARGB32(buff, image) //test pass
            TextureFormat.ARGBFloat -> flag = decodeARGBFloat(buff, image)
            TextureFormat.RGB565 -> flag = decodeRGB565(buff, image) //test pass
            TextureFormat.BGR24 -> flag = decodeBGR24(buff, image)
            TextureFormat.R16 -> flag = decodeR16(buff, image) //test pass
            TextureFormat.DXT1 -> flag = decodeDXT1(buff, image) //test pass
            TextureFormat.DXT3 -> flag = false //C# 中未实现
            TextureFormat.DXT5 -> flag = decodeDXT5(buff, image) //test pass
            TextureFormat.RGBA4444 -> flag = decodeRGBA4444(buff, image) //test pass
            TextureFormat.BGRA32 -> flag = decodeBGRA32(buff, image) //test pass
            TextureFormat.RHalf -> flag = decodeRHalf(buff, image)
            TextureFormat.RGHalf -> flag = decodeRGHalf(buff, image)
            TextureFormat.RGBAHalf -> flag = decodeRGBAHalf(buff, image) //test pass
            TextureFormat.RFloat -> flag = decodeRFloat(buff, image)
            TextureFormat.RGFloat -> flag = decodeRGFloat(buff, image)
            TextureFormat.RGBAFloat -> flag = decodeRGBAFloat(buff, image)
            TextureFormat.RGBFloat -> flag = decodeRGBFloat(buff, image)
            TextureFormat.YUY2 -> flag = decodeYUY2(buff, image) //test pass
            TextureFormat.RGB9e5Float -> flag = decodeRGB9e5Float(buff, image) //test pass
            TextureFormat.BC6H -> flag = decodeBC6H(buff, image) //test pass
            TextureFormat.BC7 -> flag = decodeBC7(buff, image) //test pass
            TextureFormat.BC4 -> flag = decodeBC4(buff, image) //test pass
            TextureFormat.BC5 -> flag = decodeBC5(buff, image) //test pass
            TextureFormat.DXT1Crunched -> flag = decodeDXT1Crunched(buff, image) //test pass
            TextureFormat.DXT5Crunched -> flag = decodeDXT5Crunched(buff, image) //test pass
            TextureFormat.PVRTC_RGB2, TextureFormat.PVRTC_RGBA2 -> //test pass
                flag = decodePVRTC(buff, image, true)
            TextureFormat.PVRTC_RGB4, TextureFormat.PVRTC_RGBA4 -> //test pass
                flag = decodePVRTC(buff, image, false)
            TextureFormat.ETC_RGB4, TextureFormat.ETC_RGB4_3DS -> //test pass
                flag = decodeETC1(buff, image)
            TextureFormat.ATC_RGB4 -> flag = decodeATCRGB4(buff, image) //test pass
            TextureFormat.ATC_RGBA8 -> flag = decodeATCRGBA8(buff, image) //test pass
            TextureFormat.EAC_R -> flag = decodeEACR(buff, image) //test pass
            TextureFormat.EAC_R_SIGNED -> flag = decodeEACRSigned(buff, image)
            TextureFormat.EAC_RG -> flag = decodeEACRG(buff, image) //test pass
            TextureFormat.EAC_RG_SIGNED -> flag = decodeEACRGSigned(buff, image)
            TextureFormat.ETC2_RGB -> flag = decodeETC2(buff, image) //test pass
            TextureFormat.ETC2_RGBA1 -> flag = decodeETC2A1(buff, image) //test pass
            TextureFormat.ETC2_RGBA8, TextureFormat.ETC_RGBA8_3DS -> //test pass
                flag = decodeETC2A8(buff, image)
            TextureFormat.ASTC_RGB_4x4, TextureFormat.ASTC_RGBA_4x4, TextureFormat.ASTC_HDR_4x4 -> //test pass
                flag = decodeASTC(buff, image, 4)
            TextureFormat.ASTC_RGB_5x5, TextureFormat.ASTC_RGBA_5x5, TextureFormat.ASTC_HDR_5x5 -> //test pass
                flag = decodeASTC(buff, image, 5)
            TextureFormat.ASTC_RGB_6x6, TextureFormat.ASTC_RGBA_6x6, TextureFormat.ASTC_HDR_6x6 -> //test pass
                flag = decodeASTC(buff, image, 6)
            TextureFormat.ASTC_RGB_8x8, TextureFormat.ASTC_RGBA_8x8, TextureFormat.ASTC_HDR_8x8 -> //test pass
                flag = decodeASTC(buff, image, 8)
            TextureFormat.ASTC_RGB_10x10, TextureFormat.ASTC_RGBA_10x10, TextureFormat.ASTC_HDR_10x10 -> //test pass
                flag = decodeASTC(buff, image, 10)
            TextureFormat.ASTC_RGB_12x12, TextureFormat.ASTC_RGBA_12x12, TextureFormat.ASTC_HDR_12x12 -> //test pass
                flag = decodeASTC(buff, image, 12)
            TextureFormat.RG16 -> flag = decodeRG16(buff, image) //test pass
            TextureFormat.R8 -> flag = decodeR8(buff, image) //test pass
            TextureFormat.ETC_RGB4Crunched -> flag = decodeETC1Crunched(buff, image) //test pass
            TextureFormat.ETC2_RGBA8Crunched -> flag = decodeETC2A8Crunched(buff, image) //test pass
            TextureFormat.RG32 -> flag = decodeRG32(buff, image) //test pass
            TextureFormat.RGB48 -> flag = decodeRGB48(buff, image) //test pass
            TextureFormat.RGBA64 -> flag = decodeRGBA64(buff, image) //test pass
        }
        return flag
    }

    // ============ 小端读取辅助 ============

    private fun readU16LE(d: ByteArray, o: Int): Int =
        (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)

    private fun readInt32LE(d: ByteArray, o: Int): Int =
        (d[o].toInt() and 0xFF) or
            ((d[o + 1].toInt() and 0xFF) shl 8) or
            ((d[o + 2].toInt() and 0xFF) shl 16) or
            ((d[o + 3].toInt() and 0xFF) shl 24)

    private fun readFloatLE(d: ByteArray, o: Int): Float = Float.fromBits(readInt32LE(d, o))

    /** 数据长度是否足够 */
    private fun hasBytes(d: ByteArray, need: Int): Boolean = d.size >= need

    // ============ Xbox360 字节交换 ============

    private fun swapBytesForXbox(imageData: ByteArray) {
        if (platform == BuildTarget.XBOX360) {
            val n = (reader.size.toInt() / 2).coerceAtMost(imageData.size / 2)
            for (i in 0 until n) {
                val b = imageData[i * 2]
                imageData[i * 2] = imageData[i * 2 + 1]
                imageData[i * 2 + 1] = b
            }
        }
    }

    // ============ 纯 Kotlin 解码 ============

    private fun decodeAlpha8(imageData: ByteArray, image: IntArray): Boolean {
        val size = m_Width * m_Height
        if (!hasBytes(imageData, size)) return false
        for (i in 0 until size) {
            image[i] = ((imageData[i].toInt() and 0xFF) shl 24) or 0xFFFFFF //RGB=255
        }
        return true
    }

    private fun decodeARGB4444(imageData: ByteArray, image: IntArray): Boolean {
        val size = m_Width * m_Height
        if (!hasBytes(imageData, size * 2)) return false
        for (i in 0 until size) {
            val pixelOldShort = readU16LE(imageData, i * 2)
            var b = pixelOldShort and 0x000F
            var g = (pixelOldShort and 0x00F0) shr 4
            var r = (pixelOldShort and 0x0F00) shr 8
            var a = (pixelOldShort and 0xF000) shr 12
            b = (b shl 4) or b
            g = (g shl 4) or g
            r = (r shl 4) or r
            a = (a shl 4) or a
            image[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return true
    }

    private fun decodeRGB24(imageData: ByteArray, image: IntArray): Boolean {
        val size = m_Width * m_Height
        if (!hasBytes(imageData, size * 3)) return false
        for (i in 0 until size) {
            val r = imageData[i * 3].toInt() and 0xFF
            val g = imageData[i * 3 + 1].toInt() and 0xFF
            val b = imageData[i * 3 + 2].toInt() and 0xFF
            image[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return true
    }

    private fun decodeRGBA32(imageData: ByteArray, image: IntArray): Boolean {
        if (!hasBytes(imageData, outPutSize)) return false
        for (i in 0 until outPutSize step 4) {
            val r = imageData[i].toInt() and 0xFF
            val g = imageData[i + 1].toInt() and 0xFF
            val b = imageData[i + 2].toInt() and 0xFF
            val a = imageData[i + 3].toInt() and 0xFF
            image[i / 4] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return true
    }

    private fun decodeARGB32(imageData: ByteArray, image: IntArray): Boolean {
        if (!hasBytes(imageData, outPutSize)) return false
        for (i in 0 until outPutSize step 4) {
            val a = imageData[i].toInt() and 0xFF
            val r = imageData[i + 1].toInt() and 0xFF
            val g = imageData[i + 2].toInt() and 0xFF
            val b = imageData[i + 3].toInt() and 0xFF
            image[i / 4] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return true
    }

    private fun decodeARGBFloat(imageData: ByteArray, image: IntArray): Boolean {
        val size = m_Width * m_Height
        if (!hasBytes(imageData, size * 16)) return false
        for (i in 0 until size) {
            val a = readFloatLE(imageData, i * 16)
            val r = readFloatLE(imageData, i * 16 + 4)
            val g = readFloatLE(imageData, i * 16 + 8)
            val b = readFloatLE(imageData, i * 16 + 12)
            image[i] = (clampByte(roundToInt(a * 255f)) shl 24) or
                (clampByte(roundToInt(r * 255f)) shl 16) or
                (clampByte(roundToInt(g * 255f)) shl 8) or
                clampByte(roundToInt(b * 255f))
        }
        return true
    }

    private fun decodeRGBFloat(imageData: ByteArray, image: IntArray): Boolean {
        val size = m_Width * m_Height
        if (!hasBytes(imageData, size * 12)) return false
        for (i in 0 until size) {
            val r = readFloatLE(imageData, i * 12)
            val g = readFloatLE(imageData, i * 12 + 4)
            val b = readFloatLE(imageData, i * 12 + 8)
            image[i] = (0xFF shl 24) or
                (clampByte(roundToInt(r * 255f)) shl 16) or
                (clampByte(roundToInt(g * 255f)) shl 8) or
                clampByte(roundToInt(b * 255f))
        }
        return true
    }

    private fun decodeRGB565(imageData: ByteArray, image: IntArray): Boolean {
        val size = m_Width * m_Height
        if (!hasBytes(imageData, size * 2)) return false
        for (i in 0 until size) {
            val p = readU16LE(imageData, i * 2)
            val b = (p shl 3) or ((p shr 2) and 7)
            val g = ((p shr 3) and 0xFC) or ((p shr 9) and 3)
            val r = ((p shr 8) and 0xF8) or (p shr 13)
            image[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return true
    }

    private fun decodeBGR24(imageData: ByteArray, image: IntArray): Boolean {
        val size = m_Width * m_Height
        if (!hasBytes(imageData, size * 3)) return false
        for (i in 0 until size) {
            val b = imageData[i * 3].toInt() and 0xFF
            val g = imageData[i * 3 + 1].toInt() and 0xFF
            val r = imageData[i * 3 + 2].toInt() and 0xFF
            image[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return true
    }

    private fun decodeR16(imageData: ByteArray, image: IntArray): Boolean {
        val size = m_Width * m_Height
        if (!hasBytes(imageData, size * 2)) return false
        for (i in 0 until size) {
            val r = downScaleFrom16BitTo8Bit(readU16LE(imageData, i * 2))
            image[i] = (0xFF shl 24) or (r shl 16) //b=0 g=0
        }
        return true
    }

    private fun decodeRGBA4444(imageData: ByteArray, image: IntArray): Boolean {
        val size = m_Width * m_Height
        if (!hasBytes(imageData, size * 2)) return false
        for (i in 0 until size) {
            val pixelOldShort = readU16LE(imageData, i * 2)
            var b = (pixelOldShort and 0x00F0) shr 4
            var g = (pixelOldShort and 0x0F00) shr 8
            var r = (pixelOldShort and 0xF000) shr 12
            var a = pixelOldShort and 0x000F
            b = (b shl 4) or b
            g = (g shl 4) or g
            r = (r shl 4) or r
            a = (a shl 4) or a
            image[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return true
    }

    private fun decodeBGRA32(imageData: ByteArray, image: IntArray): Boolean {
        if (!hasBytes(imageData, outPutSize)) return false
        for (i in 0 until outPutSize step 4) {
            val b = imageData[i].toInt() and 0xFF
            val g = imageData[i + 1].toInt() and 0xFF
            val r = imageData[i + 2].toInt() and 0xFF
            val a = imageData[i + 3].toInt() and 0xFF
            image[i / 4] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return true
    }

    private fun decodeRHalf(imageData: ByteArray, image: IntArray): Boolean {
        if (!hasBytes(imageData, m_Width * m_Height * 2)) return false
        for (i in 0 until outPutSize step 4) {
            val r = clampByte(roundToInt(Half.toFloat(readU16LE(imageData, i / 2)) * 255f))
            image[i / 4] = (0xFF shl 24) or (r shl 16) //b=0 g=0
        }
        return true
    }

    private fun decodeRGHalf(imageData: ByteArray, image: IntArray): Boolean {
        if (!hasBytes(imageData, outPutSize * 2)) return false
        for (i in 0 until outPutSize step 4) {
            val g = clampByte(roundToInt(Half.toFloat(readU16LE(imageData, i + 2)) * 255f))
            val r = clampByte(roundToInt(Half.toFloat(readU16LE(imageData, i)) * 255f))
            image[i / 4] = (0xFF shl 24) or (r shl 16) or (g shl 8) //b=0
        }
        return true
    }

    private fun decodeRGBAHalf(imageData: ByteArray, image: IntArray): Boolean {
        if (!hasBytes(imageData, outPutSize * 2)) return false
        for (i in 0 until outPutSize step 4) {
            val b = clampByte(roundToInt(Half.toFloat(readU16LE(imageData, i * 2 + 4)) * 255f))
            val g = clampByte(roundToInt(Half.toFloat(readU16LE(imageData, i * 2 + 2)) * 255f))
            val r = clampByte(roundToInt(Half.toFloat(readU16LE(imageData, i * 2)) * 255f))
            val a = clampByte(roundToInt(Half.toFloat(readU16LE(imageData, i * 2 + 6)) * 255f))
            image[i / 4] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return true
    }

    private fun decodeRFloat(imageData: ByteArray, image: IntArray): Boolean {
        if (!hasBytes(imageData, outPutSize)) return false
        for (i in 0 until outPutSize step 4) {
            val r = clampByte(roundToInt(readFloatLE(imageData, i) * 255f))
            image[i / 4] = (0xFF shl 24) or (r shl 16) //b=0 g=0
        }
        return true
    }

    private fun decodeRGFloat(imageData: ByteArray, image: IntArray): Boolean {
        if (!hasBytes(imageData, outPutSize * 2)) return false
        for (i in 0 until outPutSize step 4) {
            val g = clampByte(roundToInt(readFloatLE(imageData, i * 2 + 4) * 255f))
            val r = clampByte(roundToInt(readFloatLE(imageData, i * 2) * 255f))
            image[i / 4] = (0xFF shl 24) or (r shl 16) or (g shl 8) //b=0
        }
        return true
    }

    private fun decodeRGBAFloat(imageData: ByteArray, image: IntArray): Boolean {
        if (!hasBytes(imageData, outPutSize * 4)) return false
        for (i in 0 until outPutSize step 4) {
            val b = clampByte(roundToInt(readFloatLE(imageData, i * 4 + 8) * 255f))
            val g = clampByte(roundToInt(readFloatLE(imageData, i * 4 + 4) * 255f))
            val r = clampByte(roundToInt(readFloatLE(imageData, i * 4) * 255f))
            val a = clampByte(roundToInt(readFloatLE(imageData, i * 4 + 12) * 255f))
            image[i / 4] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return true
    }

    private fun decodeYUY2(imageData: ByteArray, image: IntArray): Boolean {
        var p = 0
        var o = 0
        val halfWidth = m_Width / 2
        if (!hasBytes(imageData, halfWidth * m_Height * 4)) return false
        for (j in 0 until m_Height) {
            for (i in 0 until halfWidth) {
                val y0 = imageData[p++].toInt() and 0xFF
                val u0 = imageData[p++].toInt() and 0xFF
                val y1 = imageData[p++].toInt() and 0xFF
                val v0 = imageData[p++].toInt() and 0xFF
                var c = y0 - 16
                val d = u0 - 128
                val e = v0 - 128
                image[o++] = (0xFF shl 24) or
                    (clampByte((298 * c + 409 * e + 128) shr 8) shl 16) or //r
                    (clampByte((298 * c - 100 * d - 208 * e + 128) shr 8) shl 8) or //g
                    clampByte((298 * c + 516 * d + 128) shr 8) //b
                c = y1 - 16
                image[o++] = (0xFF shl 24) or
                    (clampByte((298 * c + 409 * e + 128) shr 8) shl 16) or //r
                    (clampByte((298 * c - 100 * d - 208 * e + 128) shr 8) shl 8) or //g
                    clampByte((298 * c + 516 * d + 128) shr 8) //b
            }
        }
        return true
    }

    private fun decodeRGB9e5Float(imageData: ByteArray, image: IntArray): Boolean {
        if (!hasBytes(imageData, outPutSize)) return false
        for (i in 0 until outPutSize step 4) {
            val n = readInt32LE(imageData, i)
            val scale = (n shr 27) and 0x1F
            val scalef = Math.pow(2.0, (scale - 24).toDouble())
            val b = (n shr 18) and 0x1FF
            val g = (n shr 9) and 0x1FF
            val r = n and 0x1FF
            image[i / 4] = (0xFF shl 24) or
                (clampByte(roundToInt((r * scalef * 255f).toDouble())) shl 16) or
                (clampByte(roundToInt((g * scalef * 255f).toDouble())) shl 8) or
                clampByte(roundToInt((b * scalef * 255f).toDouble()))
        }
        return true
    }

    private fun decodeRG16(imageData: ByteArray, image: IntArray): Boolean {
        val size = m_Width * m_Height
        if (!hasBytes(imageData, size * 2)) return false
        for (i in 0 until size) {
            val r = imageData[i * 2].toInt() and 0xFF //R
            val g = imageData[i * 2 + 1].toInt() and 0xFF //G
            image[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) //B=0
        }
        return true
    }

    private fun decodeR8(imageData: ByteArray, image: IntArray): Boolean {
        val size = m_Width * m_Height
        if (!hasBytes(imageData, size)) return false
        for (i in 0 until size) {
            val r = imageData[i].toInt() and 0xFF //R
            image[i] = (0xFF shl 24) or (r shl 16) //B=0 G=0
        }
        return true
    }

    private fun decodeRG32(imageData: ByteArray, image: IntArray): Boolean {
        if (!hasBytes(imageData, outPutSize * 2)) return false
        for (i in 0 until outPutSize step 4) {
            val g = downScaleFrom16BitTo8Bit(readU16LE(imageData, i + 2))
            val r = downScaleFrom16BitTo8Bit(readU16LE(imageData, i))
            image[i / 4] = (0xFF shl 24) or (r shl 16) or (g shl 8) //B=0
        }
        return true
    }

    private fun decodeRGB48(imageData: ByteArray, image: IntArray): Boolean {
        val size = m_Width * m_Height
        if (!hasBytes(imageData, size * 6)) return false
        for (i in 0 until size) {
            val b = downScaleFrom16BitTo8Bit(readU16LE(imageData, i * 6 + 4))
            val g = downScaleFrom16BitTo8Bit(readU16LE(imageData, i * 6 + 2))
            val r = downScaleFrom16BitTo8Bit(readU16LE(imageData, i * 6))
            image[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return true
    }

    private fun decodeRGBA64(imageData: ByteArray, image: IntArray): Boolean {
        if (!hasBytes(imageData, outPutSize * 2)) return false
        for (i in 0 until outPutSize step 4) {
            val b = downScaleFrom16BitTo8Bit(readU16LE(imageData, i * 2 + 4))
            val g = downScaleFrom16BitTo8Bit(readU16LE(imageData, i * 2 + 2))
            val r = downScaleFrom16BitTo8Bit(readU16LE(imageData, i * 2))
            val a = downScaleFrom16BitTo8Bit(readU16LE(imageData, i * 2 + 6))
            image[i / 4] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return true
    }

    // ============ JNI 解码（对应 C# TextureDecoder.*） ============

    private fun decodeDXT1(imageData: ByteArray, image: IntArray): Boolean {
        NativeDecoder.decodeBC1(imageData, m_Width, m_Height, image)
        return true
    }

    private fun decodeDXT5(imageData: ByteArray, image: IntArray): Boolean {
        NativeDecoder.decodeBC3(imageData, m_Width, m_Height, image)
        return true
    }

    private fun decodeBC4(imageData: ByteArray, image: IntArray): Boolean {
        NativeDecoder.decodeBC4(imageData, m_Width, m_Height, image)
        return true
    }

    private fun decodeBC5(imageData: ByteArray, image: IntArray): Boolean {
        NativeDecoder.decodeBC5(imageData, m_Width, m_Height, image)
        return true
    }

    private fun decodeBC6H(imageData: ByteArray, image: IntArray): Boolean {
        NativeDecoder.decodeBC6(imageData, m_Width, m_Height, image)
        return true
    }

    private fun decodeBC7(imageData: ByteArray, image: IntArray): Boolean {
        NativeDecoder.decodeBC7(imageData, m_Width, m_Height, image)
        return true
    }

    private fun decodePVRTC(imageData: ByteArray, image: IntArray, is2bpp: Boolean): Boolean {
        NativeDecoder.decodePVRTC(imageData, m_Width, m_Height, is2bpp, image)
        return true
    }

    private fun decodeETC1(imageData: ByteArray, image: IntArray): Boolean {
        NativeDecoder.decodeETC1(imageData, m_Width, m_Height, image)
        return true
    }

    private fun decodeETC2(imageData: ByteArray, image: IntArray): Boolean {
        NativeDecoder.decodeETC2(imageData, m_Width, m_Height, image)
        return true
    }

    private fun decodeETC2A1(imageData: ByteArray, image: IntArray): Boolean {
        NativeDecoder.decodeETC2A1(imageData, m_Width, m_Height, image)
        return true
    }

    private fun decodeETC2A8(imageData: ByteArray, image: IntArray): Boolean {
        NativeDecoder.decodeETC2A8(imageData, m_Width, m_Height, image)
        return true
    }

    private fun decodeEACR(imageData: ByteArray, image: IntArray): Boolean {
        NativeDecoder.decodeEACR(imageData, m_Width, m_Height, image)
        return true
    }

    private fun decodeEACRSigned(imageData: ByteArray, image: IntArray): Boolean {
        NativeDecoder.decodeEACRSigned(imageData, m_Width, m_Height, image)
        return true
    }

    private fun decodeEACRG(imageData: ByteArray, image: IntArray): Boolean {
        NativeDecoder.decodeEACRG(imageData, m_Width, m_Height, image)
        return true
    }

    private fun decodeEACRGSigned(imageData: ByteArray, image: IntArray): Boolean {
        NativeDecoder.decodeEACRGSigned(imageData, m_Width, m_Height, image)
        return true
    }

    private fun decodeATCRGB4(imageData: ByteArray, image: IntArray): Boolean {
        NativeDecoder.decodeATCRGB4(imageData, m_Width, m_Height, image)
        return true
    }

    private fun decodeATCRGBA8(imageData: ByteArray, image: IntArray): Boolean {
        NativeDecoder.decodeATCRGBA8(imageData, m_Width, m_Height, image)
        return true
    }

    private fun decodeASTC(imageData: ByteArray, image: IntArray, blocksize: Int): Boolean {
        NativeDecoder.decodeASTC(imageData, m_Width, m_Height, blocksize, blocksize, image)
        return true
    }

    // ============ Crunched ============

    private fun decodeDXT1Crunched(imageData: ByteArray, image: IntArray): Boolean {
        val result = unpackCrunch(imageData) ?: return false
        return decodeDXT1(result, image)
    }

    private fun decodeDXT5Crunched(imageData: ByteArray, image: IntArray): Boolean {
        val result = unpackCrunch(imageData) ?: return false
        return decodeDXT5(result, image)
    }

    private fun decodeETC1Crunched(imageData: ByteArray, image: IntArray): Boolean {
        val result = unpackCrunch(imageData) ?: return false
        return decodeETC1(result, image)
    }

    private fun decodeETC2A8Crunched(imageData: ByteArray, image: IntArray): Boolean {
        val result = unpackCrunch(imageData) ?: return false
        return decodeETC2A8(result, image)
    }

    /**
     * 对应 C# UnpackCrunch：
     * 2017.3+ 或 ETC 系列 crunched 用 Unity crunch，否则用标准 crunch；
     * 任一失败时回退尝试另一种。
     */
    private fun unpackCrunch(imageData: ByteArray): ByteArray? {
        val useUnity = version[0] > 2017 || (version[0] == 2017 && version[1] >= 3) || //2017.3 and up
            m_TextureFormat == TextureFormat.ETC_RGB4Crunched ||
            m_TextureFormat == TextureFormat.ETC2_RGBA8Crunched
        return if (useUnity) {
            NativeDecoder.unityCrunchUnpackLevel(imageData, 0)
                ?: NativeDecoder.crunchUnpackLevel(imageData, 0)
        } else {
            NativeDecoder.crunchUnpackLevel(imageData, 0)
                ?: NativeDecoder.unityCrunchUnpackLevel(imageData, 0)
        }
    }

    // ============ 工具 ============

    private fun clampByte(x: Int): Int = when {
        x > 255 -> 255
        x < 0 -> 0
        else -> x
    }

    private fun roundToInt(v: Float): Int = Math.round(v.toDouble()).toInt()

    private fun roundToInt(v: Double): Int = Math.round(v).toInt()

    companion object {
        /** 对应 C# DownScaleFrom16BitTo8Bit */
        @JvmStatic
        fun downScaleFrom16BitTo8Bit(component: Int): Int =
            ((component * 255) + 32895) shr 16
    }
}

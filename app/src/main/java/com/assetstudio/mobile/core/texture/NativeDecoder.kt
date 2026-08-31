package com.assetstudio.mobile.core.texture

/**
 * 原生纹理解码器桥接（JNI -> Texture2DDecoderNative，源自 AssetStudio 的 C++ 解码器）。
 *
 * 所有 decode* 函数将压缩纹理解码为 ARGB_8888 像素（int 数组，0xAARRGGBB）。
 * image 数组长度必须为 width * height。
 */
object NativeDecoder {
    @JvmStatic external fun nativeInit(): Int

    @JvmStatic external fun decodeBC1(data: ByteArray, width: Int, height: Int, image: IntArray)
    @JvmStatic external fun decodeBC3(data: ByteArray, width: Int, height: Int, image: IntArray)
    @JvmStatic external fun decodeBC4(data: ByteArray, width: Int, height: Int, image: IntArray)
    @JvmStatic external fun decodeBC5(data: ByteArray, width: Int, height: Int, image: IntArray)
    @JvmStatic external fun decodeBC6(data: ByteArray, width: Int, height: Int, image: IntArray)
    @JvmStatic external fun decodeBC7(data: ByteArray, width: Int, height: Int, image: IntArray)

    @JvmStatic external fun decodeETC1(data: ByteArray, width: Int, height: Int, image: IntArray)
    @JvmStatic external fun decodeETC2(data: ByteArray, width: Int, height: Int, image: IntArray)
    @JvmStatic external fun decodeETC2A1(data: ByteArray, width: Int, height: Int, image: IntArray)
    @JvmStatic external fun decodeETC2A8(data: ByteArray, width: Int, height: Int, image: IntArray)
    @JvmStatic external fun decodeEACR(data: ByteArray, width: Int, height: Int, image: IntArray)
    @JvmStatic external fun decodeEACRSigned(data: ByteArray, width: Int, height: Int, image: IntArray)
    @JvmStatic external fun decodeEACRG(data: ByteArray, width: Int, height: Int, image: IntArray)
    @JvmStatic external fun decodeEACRGSigned(data: ByteArray, width: Int, height: Int, image: IntArray)

    @JvmStatic external fun decodeASTC(data: ByteArray, width: Int, height: Int, blockWidth: Int, blockHeight: Int, image: IntArray)
    @JvmStatic external fun decodePVRTC(data: ByteArray, width: Int, height: Int, is2bpp: Boolean, image: IntArray)

    @JvmStatic external fun decodeATCRGB4(data: ByteArray, width: Int, height: Int, image: IntArray)
    @JvmStatic external fun decodeATCRGBA8(data: ByteArray, width: Int, height: Int, image: IntArray)

    /** 解包 crunch 压缩纹理的第 level 层，返回未 crunch 的 DXT/ETC 数据；失败返回 null */
    @JvmStatic external fun crunchUnpackLevel(data: ByteArray, level: Int): ByteArray?

    /** 解包 Unity 专用 crunch 格式，返回未 crunch 的 DXT/ETC 数据；失败返回 null */
    @JvmStatic external fun unityCrunchUnpackLevel(data: ByteArray, level: Int): ByteArray?

    fun ensureLoaded() {
        // System.loadLibrary 在 object 初始化时执行
    }

    init {
        System.loadLibrary("texture2ddecoder")
    }
}

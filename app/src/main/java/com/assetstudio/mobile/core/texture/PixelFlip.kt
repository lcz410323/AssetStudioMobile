package com.assetstudio.mobile.core.texture

/*
 * 像素行序工具（v1.7.7 修复贴图颠倒的核心）。
 *
 * 背景：Unity Texture2D 的原始数据按 OpenGL 习惯存储——row 0 是图像【底部】；
 * Android Bitmap / PNG / 常见图像格式 row 0 是图像【顶部】。
 * 原版 C# AssetStudio 在 Texture2DConverter.GetImage() 里用
 * bitmap.RotateFlip(RotateFlipType.RotateNoneFlipY) 完成这个转换。
 * 移植版此前只有 BitmapExt 封装做了翻转，预览/导出/替换的直连路径遗漏，
 * 导致所有贴图上下颠倒。
 *
 * 本对象是全项目【唯一】的翻转实现，四条路径统一使用：
 * - MainViewModel.decodeTexture（预览）
 * - AssetExporter.decodeTexture（导出）
 * - BitmapExt.decodeBitmap/decodePixels（通用封装）
 * - AssetReplacer.encodeTexture（替换回写，方向相反：顶部序 → 底部序）
 */
object PixelFlip {

    /**
     * 原地垂直翻转 width*height 的 0xAARRGGBB 像素数组。
     * 非法尺寸（宽<=0 / 高<=1 / 数组过小）时直接返回，不抛异常。
     */
    fun flipVerticalInPlace(pixels: IntArray, width: Int, height: Int) {
        if (width <= 0 || height <= 1) return
        if (pixels.size < width * height) return
        val halfHeight = height / 2
        for (y in 0 until halfHeight) {
            val topRow = y * width
            val bottomRow = (height - 1 - y) * width
            for (x in 0 until width) {
                val temp = pixels[topRow + x]
                pixels[topRow + x] = pixels[bottomRow + x]
                pixels[bottomRow + x] = temp
            }
        }
    }

    /** 返回垂直翻转后的副本（原数组不变） */
    fun flipVertical(pixels: IntArray, width: Int, height: Int): IntArray {
        val copy = pixels.copyOf()
        flipVerticalInPlace(copy, width, height)
        return copy
    }
}

package com.assetstudio.mobile.export

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import com.assetstudio.mobile.core.ClassIDType
import com.assetstudio.mobile.core.classes.AudioClip
import com.assetstudio.mobile.core.classes.Font
import com.assetstudio.mobile.core.classes.Material
import com.assetstudio.mobile.core.classes.Mesh
import com.assetstudio.mobile.core.classes.MovieTexture
import com.assetstudio.mobile.core.classes.MonoBehaviour
import com.assetstudio.mobile.core.classes.Shader
import com.assetstudio.mobile.core.classes.Sprite
import com.assetstudio.mobile.core.classes.TextAsset
import com.assetstudio.mobile.core.classes.Texture2D
import com.assetstudio.mobile.core.classes.UnityObject
import com.assetstudio.mobile.core.classes.VideoClip
import com.assetstudio.mobile.core.serialized.TypeTreeHelper
import com.assetstudio.mobile.core.texture.PixelFlip
import com.assetstudio.mobile.core.texture.TextureConverter
import com.assetstudio.mobile.render.RenderDumper
import java.io.ByteArrayOutputStream
import java.io.File

/*
 * 资产导出器（对应 AssetStudio Exporter 的常用子集）：
 * - Texture2D / Sprite → PNG
 * - Mesh → OBJ（顶点/法线/UV0/子网格分组）
 * - Material / Shader → 文本转储（RenderDumper）
 * - AudioClip → WAV（可解码时）或原始数据
 * - TextAsset → 文本
 * - Font → 字体文件
 * - MonoBehaviour / 其他 → TypeTree 转储或原始数据
 */
object AssetExporter {

    class ExportResult(val success: Boolean, val message: String)

    /** SAF（ContentResolver + Uri）入口 */
    fun export(obj: UnityObject, resolver: ContentResolver, uri: Uri): ExportResult {
        return export(obj) {
            resolver.openOutputStream(uri, "wt")
                ?: throw IllegalStateException("无法打开目标文件")
        }
    }

    /** 直写文件入口（配合应用内文件管理器，后缀完全由调用方控制） */
    fun exportToFile(obj: UnityObject, file: File): ExportResult {
        return export(obj) {
            file.parentFile?.mkdirs()
            file.outputStream()
        }
    }

    fun export(obj: UnityObject, openOutput: () -> java.io.OutputStream): ExportResult {
        return try {
            when (obj) {
                is Texture2D -> exportBitmap(decodeTexture(obj), openOutput)
                is Sprite -> exportBitmap(decodeSprite(obj), openOutput)
                is Mesh -> {
                    val text = ObjExporter.write(obj)
                    writeBytes(openOutput, text.toByteArray(Charsets.UTF_8))
                    ExportResult(
                        true,
                        "已导出 OBJ（${obj.m_VertexCount} 顶点，${obj.m_SubMeshes.size} 子网格，${obj.m_Indices.size / 3} 三角形）"
                    )
                }
                is Material -> {
                    writeBytes(openOutput, RenderDumper.dumpMaterial(obj).toByteArray(Charsets.UTF_8))
                    ExportResult(true, "已导出材质转储")
                }
                is Shader -> {
                    writeBytes(openOutput, RenderDumper.dumpShader(obj).toByteArray(Charsets.UTF_8))
                    ExportResult(true, "已导出着色器转储")
                }
                is TextAsset -> {
                    writeBytes(openOutput, obj.m_Script)
                    ExportResult(true, "已导出文本")
                }
                is AudioClip -> {
                    val wav = obj.toWav()
                    if (wav != null) {
                        writeBytes(openOutput, wav)
                        ExportResult(true, "已导出 WAV 音频")
                    } else {
                        val data = obj.getAudioData()
                        writeBytes(openOutput, data)
                        ExportResult(true, "已导出原始音频数据（${data.size} 字节，未能转 WAV）")
                    }
                }
                is Font -> {
                    val fontData = obj.m_FontData
                    if (fontData != null && fontData.isNotEmpty()) {
                        writeBytes(openOutput, fontData)
                        ExportResult(true, "已导出字体")
                    } else {
                        ExportResult(false, "该字体不含内嵌字体数据")
                    }
                }
                is MovieTexture -> {
                    writeBytes(openOutput, obj.m_MovieData)
                    ExportResult(true, "已导出视频数据")
                }
                is VideoClip -> {
                    val data = try { obj.m_VideoData.getData() } catch (e: Exception) { null }
                    if (data != null && data.isNotEmpty()) {
                        writeBytes(openOutput, data)
                        ExportResult(true, "已导出视频数据")
                    } else {
                        ExportResult(false, "视频数据为空")
                    }
                }
                is MonoBehaviour -> exportMonoBehaviour(obj, openOutput)
                else -> exportDump(obj, openOutput)
            }
        } catch (e: Exception) {
            ExportResult(false, "导出失败: ${e.message}")
        }
    }

    // ============================ 贴图 ============================

    fun decodeTexture(texture: Texture2D): Bitmap? {
        return try {
            if (texture.m_Width * texture.m_Height > 33_554_432) return null
            val pixels = TextureConverter(texture).decodeToPixels() ?: return null
            // Unity 纹理 row 0 = 底部（OpenGL 习惯），Bitmap row 0 = 顶部
            // 对应 C# RotateFlip(RotateNoneFlipY)；漏掉此步导出的 PNG 上下颠倒（v1.7.7 修复）
            PixelFlip.flipVerticalInPlace(pixels, texture.m_Width, texture.m_Height)
            Bitmap.createBitmap(pixels, texture.m_Width, texture.m_Height, Bitmap.Config.ARGB_8888)
        } catch (e: Throwable) {
            null
        }
    }

    fun decodeSprite(sprite: Sprite): Bitmap? {
        return try {
            val info = sprite.getTextureInfo() ?: return null
            val full = decodeTexture(info.texture) ?: return null
            val rect = info.textureRect
            val x = rect.x.toInt().coerceIn(0, full.width - 1)
            val y = rect.y.toInt().coerceIn(0, full.height - 1)
            val w = rect.width.toInt().coerceAtMost(full.width - x).coerceAtLeast(1)
            val h = rect.height.toInt().coerceAtMost(full.height - y).coerceAtLeast(1)
            val top = (full.height - y - h).coerceIn(0, full.height - 1)
            Bitmap.createBitmap(full, x, top, w, h)
        } catch (e: Throwable) {
            null
        }
    }

    private fun exportBitmap(bitmap: Bitmap?, openOutput: () -> java.io.OutputStream): ExportResult {
        if (bitmap == null) return ExportResult(false, "贴图解码失败（格式不支持或数据缺失）")
        val out = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
            return ExportResult(false, "PNG 编码失败")
        }
        writeBytes(openOutput, out.toByteArray())
        return ExportResult(true, "已导出 PNG（${bitmap.width}x${bitmap.height}）")
    }

    // ============================ 其他类型 ============================

    private fun exportMonoBehaviour(obj: MonoBehaviour, openOutput: () -> java.io.OutputStream): ExportResult {
        val typeTree = obj.reader.serializedType?.m_Type
        val dump = if (typeTree != null) {
            try {
                obj.reader.reset()
                TypeTreeHelper.readTypeString(typeTree, obj.reader)
            } catch (e: Exception) {
                null
            }
        } else null
        if (dump != null) {
            writeBytes(openOutput, dump.toByteArray(Charsets.UTF_8))
            return ExportResult(true, "已导出 TypeTree 转储")
        }
        return exportDump(obj, openOutput)
    }

    private fun exportDump(obj: UnityObject, openOutput: () -> java.io.OutputStream): ExportResult {
        val info = obj.assetsFile.m_Objects.firstOrNull { it.m_PathID == obj.m_PathID }
            ?: return ExportResult(false, "对象信息缺失")
        val bytes = obj.assetsFile.getRawObjectBytes(info)
        writeBytes(openOutput, bytes)
        return ExportResult(true, "已导出原始数据（${bytes.size} 字节）")
    }

    // ============================ IO ============================

    private fun writeBytes(openOutput: () -> java.io.OutputStream, data: ByteArray) {
        openOutput().use { out ->
            out.write(data)
            out.flush()
        }
    }

    /** 按类型推断导出文件扩展名 */
    fun suggestedExtension(obj: UnityObject): String = when (obj) {
        is Texture2D, is Sprite -> "png"
        is Mesh -> "obj"
        is Material, is Shader -> "txt"
        is TextAsset -> "txt"
        is AudioClip -> "wav"
        is Font -> "ttf"
        is MovieTexture -> "ogv"
        is VideoClip -> "mp4"
        is MonoBehaviour -> "txt"
        else -> "dat"
    }
}

package com.assetstudio.mobile.export

import com.assetstudio.mobile.core.classes.Mesh
import java.util.Locale

/*
 * OBJ 网格导出器：Mesh 解析数据 → Wavefront OBJ 文本。
 *
 * - 顶点 / 法线 / UV0 全量输出（v / vn / vt，均为 1-based 索引）
 * - 子网格映射为 g 分组（submesh_N），面索引形如 v/vt/vn，
 *   缺 UV 时退化为 v//vn，缺法线时退化为 v/vt
 * - 数字统一 %.6f + Locale.ROOT（避免本地化小数点）
 * - 越界索引（退化三角形）整面跳过，保证产出可被建模软件重新导入
 */
object ObjExporter {

    class ExportException(message: String) : Exception(message)

    fun write(mesh: Mesh): String {
        val vertices = mesh.m_Vertices
            ?: throw ExportException("该 Mesh 没有可用的顶点数据（可能为压缩网格且解包失败）")
        val vertexCount = vertices.size / 3
        if (vertexCount == 0 || mesh.m_Indices.isEmpty()) {
            throw ExportException("该 Mesh 没有可导出的三角形")
        }
        val normals = mesh.m_Normals
        val uvs = mesh.getUV(0)

        val sb = StringBuilder(vertexCount * 64)
        sb.appendLine("# Exported by AssetStudioMobile")
        sb.appendLine("o ${mesh.displayName.replace(Regex("\\s+"), "_")}")

        // ---------- 顶点 ----------
        var i = 0
        while (i + 2 < vertices.size) {
            sb.appendLine("v ${fmt(vertices[i])} ${fmt(vertices[i + 1])} ${fmt(vertices[i + 2])}")
            i += 3
        }
        // ---------- 法线 ----------
        if (normals != null && normals.size >= vertexCount * 3) {
            i = 0
            while (i + 2 < normals.size) {
                sb.appendLine("vn ${fmt(normals[i])} ${fmt(normals[i + 1])} ${fmt(normals[i + 2])}")
                i += 3
            }
        }
        // ---------- UV0 ----------
        if (uvs != null && uvs.size >= vertexCount * 2) {
            i = 0
            while (i + 1 < uvs.size) {
                sb.appendLine("vt ${fmt(uvs[i])} ${fmt(uvs[i + 1])}")
                i += 2
            }
        }

        // ---------- 面按子网格分组 ----------
        // m_Indices 由解析器按子网格顺序依次填充，这里按各子网格 indexCount 累计切分
        val hasN = normals != null && normals.size >= vertexCount * 3
        val hasT = uvs != null && uvs.size >= vertexCount * 2
        val indices = mesh.m_Indices
        var cursor = 0
        val subMeshes = mesh.m_SubMeshes
        if (subMeshes.isEmpty()) {
            writeFaces(sb, indices, 0, indices.size, vertexCount, hasN, hasT)
        } else {
            for ((si, sm) in subMeshes.withIndex()) {
                val count = sm.indexCount.toInt()
                sb.appendLine("g submesh_$si")
                writeFaces(sb, indices, cursor, cursor + count, vertexCount, hasN, hasT)
                cursor += count
            }
        }
        if (cursor < indices.size) { // 兜底：子网格计数之和与索引总数不一致时补齐
            sb.appendLine("g submesh_extra")
            writeFaces(sb, indices, cursor, indices.size, vertexCount, hasN, hasT)
        }
        return sb.toString()
    }

    private fun writeFaces(
        sb: StringBuilder,
        indices: List<Long>,
        from: Int,
        until: Int,
        vertexCount: Int,
        hasN: Boolean,
        hasT: Boolean
    ) {
        var j = from
        while (j + 2 < until.coerceAtMost(indices.size)) {
            val a = indices[j]
            val b = indices[j + 1]
            val c = indices[j + 2]
            j += 3
            // 越界索引整面跳过（退化数据保护）
            if (a < 0 || a >= vertexCount || b < 0 || b >= vertexCount || c < 0 || c >= vertexCount) continue
            sb.appendLine("f ${ref(a, hasN, hasT)} ${ref(b, hasN, hasT)} ${ref(c, hasN, hasT)}")
        }
    }

    /** 面 vertex 引用：v/vt/vn（全有）→ v//vn（无 UV）→ v/vt（无法线）→ v（全无） */
    private fun ref(idx: Long, hasN: Boolean, hasT: Boolean): String {
        val v = idx + 1
        return when {
            hasN && hasT -> "$v/$v/$v"
            hasN -> "$v//$v"
            hasT -> "$v/$v"
            else -> "$v"
        }
    }

    private fun fmt(v: Float): String {
        val s = String.format(Locale.ROOT, "%.6f", v)
        return if (s == "-0.000000") "0.000000" else s
    }
}

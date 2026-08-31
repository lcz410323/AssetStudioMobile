package com.assetstudio.mobile.replace

import com.assetstudio.mobile.core.ObjectReader
import com.assetstudio.mobile.core.classes.ChannelInfo
import com.assetstudio.mobile.core.classes.Mesh
import com.assetstudio.mobile.core.classes.PPtr
import com.assetstudio.mobile.core.classes.SubMesh
import com.assetstudio.mobile.core.classes.BlendShapeData
import com.assetstudio.mobile.core.classes.CompressedMesh
import com.assetstudio.mobile.core.classes.StreamingInfo
import com.assetstudio.mobile.core.classes.VertexData
import com.assetstudio.mobile.core.io.BuildTarget
import com.assetstudio.mobile.core.io.EndianType

/*
 * Mesh 对象字节重写器（Unity 5.0+）。
 *
 * 策略：按 Mesh 解析器（core/classes/Mesh.kt）的精确字段顺序重走一遍原对象，
 * 替换网格相关的数据段（子网格/索引缓冲/顶点数据/包围盒），其余字段
 * （名称、可读标志、meshUsageFlags、cookingOptions、meshMetrics 等）原值保留；
 * 骨骼/混合形等依赖原顶点布局的数据段清空。
 *
 * 关键约束：
 * - 重走结束位置必须恰好等于对象末尾（byteStart+byteSize），否则视为布局
 *   镜像失配，抛异常拒绝输出（防止像 v1.3.4 贴图重写那样的整体偏移事故）；
 * - 顶点通道表保留原网格的通道集合（切线/UV0/颜色等原样保留通道位），
 *   新数据按通道索引升序交错排布，通道表 offset 与缓冲布局严格一致；
 * - 索引缓冲 16/32 位按新顶点数决定（≤65535 用 u16），重走原缓冲时
 *   按原格式的对齐规则（u16 补齐到 4 字节，u32 不对齐）前进。
 */
class MeshObjectRewriter(private val mesh: Mesh) {

    class RewriteException(message: String) : Exception(message)

    private val version: IntArray = mesh.version
    private val little: Boolean = mesh.reader.endian == EndianType.LittleEndian

    // 顶点通道索引（2018+：kShaderChannel 顺序；2018-：切线在 5）
    private val chnVertex = 0
    private val chnNormal = 1
    private val chnTangent = if (version[0] >= 2018) 2 else 5
    private val chnColor = if (version[0] >= 2018) 3 else 2
    private val chnUv0 = if (version[0] >= 2018) 4 else 3

    /** 原顶点通道表（可能为 null：压缩网格网格或旧版本） */
    private val origChannels: Array<ChannelInfo>? = mesh.m_VertexData?.m_Channels

    // ============================ 对外入口 ============================

    fun buildReplacement(objData: ObjImporter.ObjMeshData): ByteArray {
        if (version[0] < 5) {
            throw RewriteException("暂不支持 Unity 5.0 以下的 Mesh 替换（当前 ${version[0]}.${version[1]}）")
        }
        validate(objData)

        val objInfo = mesh.assetsFile.m_Objects.firstOrNull { it.m_PathID == mesh.m_PathID }
            ?: throw RewriteException("ObjectInfo not found for Mesh ${mesh.m_PathID}")
        val src = ObjectReader(mesh.reader.fileBuffer, mesh.assetsFile, objInfo)
        val out = Writer(little)
        val objectStart = mesh.reader.byteStart.toInt()
        val objectEnd = objectStart + mesh.reader.byteSize.toInt()

        /** 原样复制 [start,end) 区间的原始字节 */
        fun passthrough(rangeStart: Int, rangeEnd: Int) {
            out.bytes(
                mesh.reader.fileBuffer.copyOfRange(rangeStart, rangeEnd.coerceAtMost(mesh.reader.fileBuffer.size))
            )
        }

        // ---------- 父类继承链 ----------
        // EditorExtension：NoTarget 平台时两个 PPtr，原样保留
        if (src.platform == BuildTarget.NoTarget) {
            val s = src.position
            PPtr(src); PPtr(src)
            passthrough(s, src.position)
        }
        // NamedObject.m_Name：保留原名称字节
        run {
            val s = src.position
            src.readAlignedString()
            passthrough(s, src.position)
        }

        // ---------- m_SubMeshes（重写） ----------
        walkSubMeshes(src)
        val use16 = objData.vertexCount <= 65535
        writeSubMeshes(out, objData, use16)

        // ---------- m_Shapes（4.1+）：清空 ----------
        walkBlendShapes(src)
        writeEmptyBlendShapes(out)

        // ---------- 骨骼（4.3+）：bindPose/boneNameHashes 清空，rootBone 保留 ----------
        var rootBoneHash = 0L
        run {
            val m_BindPoseSize = src.readArrayCount(16 * 4)
            src.position += m_BindPoseSize * 64
            out.i32(0)
            val hashesSize = src.readArrayCount(4)
            src.position += hashesSize * 4
            out.i32(0)
            rootBoneHash = src.readUInt32()
            out.u32(rootBoneHash)
        }

        // 2019+：BonesAABB / VariableBoneCountWeights 清空
        if (version[0] >= 2019) {
            val aabbSize = src.readArrayCount()
            src.position += aabbSize * 24
            out.i32(0)
            val wSize = src.readArrayCount(4)
            src.position += wSize * 4
            out.i32(0)
        }

        // ---------- 标志块：原样保留 ----------
        var meshCompression = 0
        run {
            val s = src.position
            meshCompression = src.readUInt8()
            if (version[0] >= 4) {
                if (version[0] < 5) src.readUInt8() // m_StreamCompression
                src.readBoolean()                   // m_IsReadable
                src.readBoolean()                   // m_KeepVertices
                src.readBoolean()                   // m_KeepIndices
            }
            src.alignStream()
            passthrough(s, src.position)
        }

        // ---------- IndexFormat（2017.4+ / 2017.3 特例）：重写为按新顶点数决定 ----------
        val hasIndexFormat = (version[0] > 2017 || (version[0] == 2017 && version[1] >= 4)) ||
            ((version[0] == 2017 && version[1] == 3 && version[2] == 1) && src.buildType?.isPatch == true) ||
            ((version[0] == 2017 && version[1] == 3) && meshCompression == 0)
        var origUse16 = true
        if (hasIndexFormat) {
            origUse16 = src.readInt32() == 0
        }
        if (hasIndexFormat) {
            out.i32(if (use16) 0 else 1)
        }

        // ---------- 索引缓冲（重写） ----------
        run {
            val size = src.readArrayCount(2)
            if (origUse16) {
                src.position += size / 2 * 2
                src.alignStream()
            } else {
                src.position += size / 4 * 4
            }
        }
        writeIndexBuffer(out, objData, use16)

        // ---------- m_VertexData（重写，保留原通道集合） ----------
        walkVertexData(src)
        writeVertexData(out, objData)

        // ---------- m_CompressedMesh（2.6+）：清空 ----------
        walkCompressedMesh(src)
        writeEmptyCompressedMesh(out)

        // ---------- m_LocalAABB：重写为新包围盒（解析器直接跳 24 字节） ----------
        src.position += 24
        for (a in 0 until 3) out.f32(objData.boundsMin[a])
        for (a in 0 until 3) out.f32(objData.boundsMax[a])

        // ---------- 尾部字段 ----------
        out.i32(src.readInt32()) // m_MeshUsageFlags
        if (version[0] > 2022 || (version[0] == 2022 && version[1] >= 1)) {
            out.i32(src.readInt32()) // m_CookingOptions
        }
        if (version[0] >= 5) {
            run { // m_BakedConvexCollisionMesh / m_BakedTriangleCollisionMesh 清空
                src.readUInt8Array(); src.alignStream()
                src.readUInt8Array(); src.alignStream()
                out.i32(0); out.i32(0)
            }
        }
        if (version[0] > 2018 || (version[0] == 2018 && version[1] >= 2)) {
            out.f32(src.readSingle()) // m_MeshMetrics[0]
            out.f32(src.readSingle()) // m_MeshMetrics[1]
        }
        if (version[0] > 2018 || (version[0] == 2018 && version[1] >= 3)) {
            src.alignStream()
            walkStreamingInfo(src)
            out.align()
            writeEmptyStreamingInfo(out)
        }

        // ---------- 完整性校验 ----------
        if (src.position != objectEnd) {
            throw RewriteException(
                "布局校验失败：解析停在 ${src.position}，对象末尾为 $objectEnd"
            )
        }
        return out.toByteArray()
    }

    // ============================ 子网格 ============================

    private fun walkSubMeshes(src: ObjectReader) {
        val count = src.readArrayCount()
        repeat(count) { SubMesh(src) }
    }

    private fun writeSubMeshes(out: Writer, objData: ObjImporter.ObjMeshData, use16: Boolean) {
        val elemSize = if (use16) 2 else 4
        val groups = objData.groups.ifEmpty {
            listOf(ObjImporter.ObjMeshData.Group("default", 0, objData.indices.size))
        }
        out.i32(groups.size)
        for (g in groups) {
            // 该组引用的顶点区间
            var minV = Int.MAX_VALUE
            var maxV = -1
            for (i in g.firstIndex until g.firstIndex + g.indexCount) {
                val v = objData.indices[i]
                if (v < minV) minV = v
                if (v > maxV) maxV = v
            }
            out.u32((g.firstIndex * elemSize).toLong())      // firstByte
            out.u32(g.indexCount.toLong())                    // indexCount
            out.i32(0)                                        // topology = Triangles
            if (version[0] > 2017 || (version[0] == 2017 && version[1] >= 3)) {
                out.u32(0L)                                   // baseVertex
            }
            if (version[0] >= 3) {
                out.u32(minV.toLong())                        // firstVertex
                out.u32(((maxV - minV) + 1).toLong())         // vertexCount
                for (a in 0 until 3) out.f32(objData.boundsMin[a]) // localAABB
                for (a in 0 until 3) out.f32(objData.boundsMax[a])
            }
        }
    }

    // ============================ BlendShape ============================

    private fun walkBlendShapes(src: ObjectReader) {
        BlendShapeData(src)
    }

    private fun writeEmptyBlendShapes(out: Writer) {
        if (version[0] > 4 || (version[0] == 4 && version[1] >= 3)) { // 4.3+：4 个空数组
            out.i32(0) // vertices
            out.i32(0) // shapes
            out.i32(0) // channels
            out.i32(0) // fullWeights
        } else { // 4.1-4.2：shapes + shapeVertices
            out.i32(0)
            out.i32(0)
        }
    }

    // ============================ 索引缓冲 ============================

    private fun writeIndexBuffer(out: Writer, objData: ObjImporter.ObjMeshData, use16: Boolean) {
        if (use16) {
            val buf = ByteArray(objData.indices.size * 2)
            objData.indices.forEachIndexed { i, v ->
                if (v > 65535) throw RewriteException("顶点索引 $v 超出 u16 范围")
                buf[i * 2] = (v and 0xFF).toByte()
                buf[i * 2 + 1] = (v shr 8 and 0xFF).toByte()
            }
            out.i32(buf.size)
            out.bytes(buf)
            out.align()
        } else {
            val buf = ByteArray(objData.indices.size * 4)
            objData.indices.forEachIndexed { i, v ->
                buf[i * 4] = (v and 0xFF).toByte()
                buf[i * 4 + 1] = (v shr 8 and 0xFF).toByte()
                buf[i * 4 + 2] = (v shr 16 and 0xFF).toByte()
                buf[i * 4 + 3] = (v shr 24 and 0xFF).toByte()
            }
            out.i32(buf.size)
            out.bytes(buf) // u32 索引缓冲解析器不做对齐
        }
    }

    // ============================ 顶点数据 ============================

    private fun walkVertexData(src: ObjectReader) {
        VertexData(src)
    }

    private class ChannelPlan(val chn: Int, val dim: Int, var offset: Int, val data: FloatArray)

    private fun writeVertexData(out: Writer, objData: ObjImporter.ObjMeshData) {
        val vertexCount = objData.vertexCount
        val origPresent = HashSet<Int>()
        origChannels?.forEachIndexed { chn, c ->
            if (c.dimension > 0) origPresent.add(chn)
        }
        if (origPresent.isEmpty()) { // 原网格无可用通道表 → 至少写位置
            origPresent.add(chnVertex)
            origPresent.add(chnNormal)
        }
        if (chnVertex !in origPresent) origPresent.add(chnVertex) // 位置通道必备

        val plans = ArrayList<ChannelPlan>()
        fun addChannel(chn: Int, dim: Int, data: FloatArray) {
            if (data.size != vertexCount * dim) {
                throw RewriteException("通道 $chn 数据长度不匹配: ${data.size} != ${vertexCount * dim}")
            }
            plans.add(ChannelPlan(chn, dim, 0, data))
        }

        addChannel(chnVertex, 3, objData.positions)
        if (chnNormal in origPresent) addChannel(chnNormal, 3, objData.normals)
        if (chnUv0 in origPresent) {
            addChannel(chnUv0, 2, objData.uvs ?: FloatArray(vertexCount * 2))
        }
        if (chnTangent in origPresent) {
            val tangents = if (objData.uvs != null) {
                computeTangents(objData)
            } else {
                // 无 UV 时无法推导切线方向，用 Unity 默认占位 (1,0,0,1)（w 为 handedness，禁为 0）
                FloatArray(vertexCount * 4) { i -> if (i % 4 == 0 || i % 4 == 3) 1f else 0f }
            }
            addChannel(chnTangent, 4, tangents)
        }
        if (chnColor in origPresent) {
            addChannel(chnColor, 4, FloatArray(vertexCount * 4) { 1f }) // 白色
        }

        // ★ 按通道索引升序排列后统一分配字节偏移，保证
        //   交错缓冲布局与下方通道描述表的 offset 完全一致
        plans.sortBy { it.chn }
        var stride = 0
        for (p in plans) {
            p.offset = stride
            stride += p.dim * 4
        }
        for (p in plans) {
            if (p.offset > 255) {
                throw RewriteException("通道 ${p.chn} 偏移 ${p.offset} 超出单字节上限（顶点布局过大）")
            }
        }

        // 写入顶点交错缓冲
        val buffer = ByteArray(vertexCount * stride)
        val tmp = ByteArray(4)
        for (v in 0 until vertexCount) {
            val base = v * stride
            for (p in plans) {
                for (d in 0 until p.dim) {
                    val bits = java.lang.Float.floatToIntBits(p.data[v * p.dim + d])
                    if (little) {
                        tmp[0] = (bits and 0xFF).toByte()
                        tmp[1] = (bits shr 8 and 0xFF).toByte()
                        tmp[2] = (bits shr 16 and 0xFF).toByte()
                        tmp[3] = (bits shr 24 and 0xFF).toByte()
                    } else {
                        tmp[0] = (bits shr 24 and 0xFF).toByte()
                        tmp[1] = (bits shr 16 and 0xFF).toByte()
                        tmp[2] = (bits shr 8 and 0xFF).toByte()
                        tmp[3] = (bits and 0xFF).toByte()
                    }
                    System.arraycopy(tmp, 0, buffer, base + p.offset + d * 4, 4)
                }
            }
        }

        // 序列化（5.0+：无 m_Streams；2018- 多一个 m_CurrentChannels u32）
        var mask = 0L
        for (p in plans) mask = mask or (1L shl p.chn)
        if (version[0] < 2018) out.u32(mask)   // m_CurrentChannels
        out.u32(vertexCount.toLong())          // m_VertexCount

        // 通道描述表（保持原条目数；未启用通道 dim=0），offset 与上面分配一致
        val channelCount = origChannels?.size ?: ((if (version[0] >= 2018) 14 else 6))
        out.i32(channelCount)
        for (chn in 0 until channelCount) {
            val p = plans.firstOrNull { it.chn == chn }
            out.u8(0)                          // stream
            out.u8(p?.offset ?: 0)             // offset
            out.u8(0)                          // format = float32
            out.u8(p?.dim ?: 0)                // dimension
        }

        out.i32(buffer.size)
        out.bytes(buffer)
        out.align()
    }

    /** 标准切线计算（Lengyel 算法，含 Gram-Schmidt 正交化） */
    private fun computeTangents(objData: ObjImporter.ObjMeshData): FloatArray {
        val n = objData.vertexCount
        val tan = FloatArray(n * 4)
        val tanAcc = FloatArray(n * 3)
        val uvs = objData.uvs ?: return tan

        val pos = objData.positions
        val nrm = objData.normals
        val idx = objData.indices
        for (t in idx.indices step 3) {
            val i0 = idx[t]; val i1 = idx[t + 1]; val i2 = idx[t + 2]
            val p0 = i0 * 3; val p1 = i1 * 3; val p2 = i2 * 3
            val u0 = i0 * 2; val u1 = i1 * 2; val u2 = i2 * 2

            val e1x = pos[p1] - pos[p0]
            val e1y = pos[p1 + 1] - pos[p0 + 1]
            val e1z = pos[p1 + 2] - pos[p0 + 2]
            val e2x = pos[p2] - pos[p0]
            val e2y = pos[p2 + 1] - pos[p0 + 1]
            val e2z = pos[p2 + 2] - pos[p0 + 2]
            val du1x = uvs[u1] - uvs[u0]
            val du1y = uvs[u1 + 1] - uvs[u0 + 1]
            val du2x = uvs[u2] - uvs[u0]
            val du2y = uvs[u2 + 1] - uvs[u0 + 1]

            val det = du1x * du2y - du2x * du1y
            if (kotlin.math.abs(det) < 1e-12f) continue
            val r = 1f / det
            // tangent 方向 = (e1 * du2y - e2 * du1y) / det
            val tx = (e1x * du2y - e2x * du1y) * r
            val ty = (e1y * du2y - e2y * du1y) * r
            val tz = (e1z * du2y - e2z * du1y) * r
            for (base in intArrayOf(i0 * 3, i1 * 3, i2 * 3)) {
                tanAcc[base] += tx
                tanAcc[base + 1] += ty
                tanAcc[base + 2] += tz
            }
        }
        for (v in 0 until n) {
            val b = v * 4
            val nx = nrm[v * 3]; val ny = nrm[v * 3 + 1]; val nz = nrm[v * 3 + 2]
            var tx = tanAcc[v * 3]; var ty = tanAcc[v * 3 + 1]; var tz = tanAcc[v * 3 + 2]
            // Gram-Schmidt 正交化：t = normalize(t - n * dot(n, t))
            val dot = nx * tx + ny * ty + nz * tz
            tx -= nx * dot; ty -= ny * dot; tz -= nz * dot
            val len = kotlin.math.sqrt(tx * tx + ty * ty + tz * tz)
            if (len > 1e-12f) {
                tan[b] = tx / len; tan[b + 1] = ty / len; tan[b + 2] = tz / len
                // handedness = sign(dot(cross(n, t), t2))，t2 取 UV 副切线方向的近似：省略精确副切线，
                // 用右手系默认 +1（OBJ 导入的 UV 布局与 Unity 一致）
                tan[b + 3] = 1f
            } else {
                tan[b] = 1f; tan[b + 1] = 0f; tan[b + 2] = 0f; tan[b + 3] = 1f
            }
        }
        return tan
    }

    // ============================ CompressedMesh ============================

    private fun walkCompressedMesh(src: ObjectReader) {
        CompressedMesh(src)
    }

    /** 空 PackedFloatVector：u32 num + f32 range + f32 start + i32 0 + u8 bitsize + 对齐 = 20 字节 */
    private fun emptyPackedFloat(out: Writer) {
        out.u32(0L); out.f32(0f); out.f32(0f)
        out.i32(0)
        out.u8(0); out.align()
    }

    /** 空 PackedIntVector：u32 num + i32 0 + u8 bitsize + 对齐 = 12 字节 */
    private fun emptyPackedInt(out: Writer) {
        out.u32(0L)
        out.i32(0)
        out.u8(0); out.align()
    }

    private fun writeEmptyCompressedMesh(out: Writer) {
        emptyPackedFloat(out)   // m_Vertices
        emptyPackedFloat(out)   // m_UV
        if (version[0] < 5) emptyPackedFloat(out) // m_BindPoses（仅 <5 序列化）
        emptyPackedFloat(out)   // m_Normals
        emptyPackedFloat(out)   // m_Tangents
        emptyPackedInt(out)     // m_Weights
        emptyPackedInt(out)     // m_NormalSigns
        emptyPackedInt(out)     // m_TangentSigns
        if (version[0] >= 5) emptyPackedFloat(out) // m_FloatColors（5.0+）
        emptyPackedInt(out)     // m_BoneIndices
        emptyPackedInt(out)     // m_Triangles
        if (version[0] > 3 || (version[0] == 3 && version[1] >= 5)) {
            if (version[0] >= 5) {
                out.u32(0L)     // m_UVInfo
            }
            // <5：m_Colors PackedIntVector
            if (version[0] < 5) emptyPackedInt(out)
        }
    }

    // ============================ StreamingInfo ============================

    private fun walkStreamingInfo(src: ObjectReader) {
        StreamingInfo(src)
    }

    private fun writeEmptyStreamingInfo(out: Writer) {
        if (version[0] >= 2020) {
            out.i64(0)
        } else {
            out.u32(0L)
        }
        out.u32(0L)             // size
        out.i32(0)              // path = ""
    }

    // ============================ 校验 ============================

    private fun validate(objData: ObjImporter.ObjMeshData) {
        if (objData.vertexCount == 0) throw RewriteException("OBJ 没有顶点")
        if (objData.indices.isEmpty()) throw RewriteException("OBJ 没有索引")
        for (v in objData.indices) {
            if (v < 0 || v >= objData.vertexCount) {
                throw RewriteException("OBJ 索引越界: $v（顶点数 ${objData.vertexCount}）")
            }
        }
        if (objData.indices.size % 3 != 0) {
            throw RewriteException("索引数不是 3 的倍数（三角形列表）")
        }
    }

    // ============================ 字节写出辅助 ============================

    private class Writer(private val little: Boolean) {
        private val out = java.io.ByteArrayOutputStream()

        fun toByteArray(): ByteArray = out.toByteArray()

        fun u8(v: Int) { out.write(v and 0xFF) }

        fun u16(v: Int) {
            if (little) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF) }
            else { out.write(v shr 8 and 0xFF); out.write(v and 0xFF) }
        }

        fun i32(v: Int) {
            if (little) {
                out.write(v and 0xFF); out.write(v shr 8 and 0xFF)
                out.write(v shr 16 and 0xFF); out.write(v shr 24 and 0xFF)
            } else {
                out.write(v shr 24 and 0xFF); out.write(v shr 16 and 0xFF)
                out.write(v shr 8 and 0xFF); out.write(v and 0xFF)
            }
        }

        fun u32(v: Long) = i32(v.toInt())

        fun i64(v: Long) {
            for (i in 0 until 8) {
                val shift = if (little) 8 * i else 8 * (7 - i)
                out.write(((v ushr shift) and 0xFF).toInt())
            }
        }

        fun f32(v: Float) = i32(java.lang.Float.floatToIntBits(v))

        fun bytes(b: ByteArray) { out.write(b) }

        fun align(alignment: Int = 4) {
            while (out.size() % alignment != 0) out.write(0)
        }
    }
}

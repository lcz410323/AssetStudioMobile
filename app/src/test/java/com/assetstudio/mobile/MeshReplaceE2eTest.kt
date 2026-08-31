package com.assetstudio.mobile

import com.assetstudio.mobile.core.classes.Mesh
import com.assetstudio.mobile.core.manager.AssetsManager
import com.assetstudio.mobile.export.ObjExporter
import com.assetstudio.mobile.replace.AssetReplacer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.math.abs

/*
 * 模型链路端到端测试：
 *
 *   合成真实 Mesh 对象字节（Unity 2019.4 布局镜像）→ SerializedFile(v17) → UnityFS bundle
 *   → AssetsManager 加载 → ObjExporter 导出 OBJ 文本
 *   → AssetReplacer.replaceMesh（OBJ 导入 + MeshObjectRewriter 重写 + 重打包）
 *   → 新 AssetsManager 重新加载 → 断言新网格数据与 OBJ 一致
 *
 * 覆盖：ObjExporter 输出格式、ObjImporter 解析、MeshObjectRewriter 全字段镜像
 * 与布局校验、两级容器重写后重新解析。
 */

class MeshReplaceE2eTest {

    // ============================ 合成 Mesh 对象 ============================

    /** 最小网格规格：位置/法线/UV/索引（internal 供 SkinnedMeshRendererPreviewTest 复用） */
    internal class MeshSpec(
        val name: String,
        val positions: FloatArray,   // 3/顶点
        val normals: FloatArray,     // 3/顶点
        val uvs: FloatArray?,        // 2/顶点；null = 不写 UV 通道
        val indices: IntArray        // u16 三角形列表
    )

    /**
     * 按 core/classes/Mesh.kt（2019.4 分支）逐字段构造对象字节。
     * 布局：name → subMeshes → shapes(空) → 骨骼(空) → 标志 → indexFormat
     *   → indexBuffer(u16) → vertexData(14 通道表) → compressedMesh(空) →
     *   AABB(24B) → usageFlags → baked(空) → metrics → streamData(空)
     */
    internal fun buildMeshObjectBytes(spec: MeshSpec): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        fun i32(v: Int) {
            o.write(v and 0xFF); o.write(v shr 8 and 0xFF)
            o.write(v shr 16 and 0xFF); o.write(v shr 24 and 0xFF)
        }
        fun u32(v: Long) = i32(v.toInt())
        fun u8(v: Int) { o.write(v and 0xFF) }
        fun f32(v: Float) = i32(java.lang.Float.floatToIntBits(v))
        fun str(s: String) {
            i32(s.length); o.write(s.toByteArray(Charsets.US_ASCII))
            while (o.size() % 4 != 0) o.write(0)
        }
        fun align4() { while (o.size() % 4 != 0) o.write(0) }
        /** 空 PackedFloatVector：20 字节 */
        fun pfv() { u32(0); f32(0f); f32(0f); i32(0); u8(0); o.write(0); o.write(0); o.write(0) }
        /** 空 PackedIntVector：12 字节 */
        fun piv() { u32(0); i32(0); u8(0); o.write(0); o.write(0); o.write(0) }

        val vCount = spec.positions.size / 3
        val hasN = spec.normals.size == vCount * 3
        val hasUv = spec.uvs != null && spec.uvs.size == vCount * 2

        // ---- NamedObject.m_Name（platform=Android ≠ NoTarget，无父类 PPtr）----
        str(spec.name)

        // ---- m_SubMeshes ×1 ----
        i32(1)
        u32(0)                                   // firstByte
        u32(spec.indices.size.toLong())          // indexCount
        i32(0)                                   // topology = Triangles
        u32(0)                                   // baseVertex（2017.3+）
        u32(0)                                   // firstVertex
        u32(vCount.toLong())                     // vertexCount
        repeat(6) { f32(0f) }                    // localAABB（解析器不读数值）

        // ---- m_Shapes（4.3+ 空结构：4 个空数组）----
        i32(0); i32(0); i32(0); i32(0)

        // ---- 骨骼：bindPose / boneNameHashes / rootBoneNameHash ----
        i32(0); i32(0); u32(0)
        // 2019+：bonesAABB / variableBoneCountWeights
        i32(0); i32(0)

        // ---- 标志块 ----
        u8(0)      // meshCompression
        u8(1)      // isReadable
        u8(0)      // keepVertices
        u8(0)      // keepIndices
        align4()

        // ---- m_IndexFormat（2017.4+）= u16 ----
        i32(0)

        // ---- m_IndexBuffer（u16 + 对齐 4）----
        i32(spec.indices.size * 2)
        for (v in spec.indices) {
            assertTrue(v <= 65535, "合成索引须在 u16 范围内")
            u8(v and 0xFF); u8(v shr 8 and 0xFF)
        }
        align4()

        // ---- m_VertexData（2018+：无 m_CurrentChannels / m_Streams 序列化）----
        val nOff = 12
        val uvOff = 12 + (if (hasN) 12 else 0)
        val stride = 12 + (if (hasN) 12 else 0) + (if (hasUv) 8 else 0)
        u32(vCount.toLong())                     // m_VertexCount
        i32(14)                                  // 通道表条目数（2018+ 固定 14）
        fun channel(off: Int, dim: Int) { u8(0); u8(off); u8(0); u8(dim) }
        channel(0, 3)                            // chn0 vertex
        channel(if (hasN) nOff else 0, if (hasN) 3 else 0)  // chn1 normal
        channel(0, 0)                            // chn2 tangent
        channel(0, 0)                            // chn3 color
        channel(if (hasUv) uvOff else 0, if (hasUv) 2 else 0)  // chn4 uv0
        repeat(9) { channel(0, 0) }              // chn5..13
        // 交错缓冲（小端 float32）
        i32(vCount * stride)
        val buf = java.nio.ByteBuffer.allocate(vCount * stride)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (v in 0 until vCount) {
            for (a in 0 until 3) buf.putFloat(spec.positions[v * 3 + a])
            if (hasN) for (a in 0 until 3) buf.putFloat(spec.normals[v * 3 + a])
            if (hasUv) for (a in 0 until 2) buf.putFloat(spec.uvs!![v * 2 + a])
        }
        o.write(buf.array())

        // ---- m_CompressedMesh（>=5：10 个空 Packed + uvInfo）----
        pfv()   // m_Vertices
        pfv()   // m_UV
        pfv()   // m_Normals
        pfv()   // m_Tangents
        piv()   // m_Weights
        piv()   // m_NormalSigns
        piv()   // m_TangentSigns
        pfv()   // m_FloatColors
        piv()   // m_BoneIndices
        piv()   // m_Triangles
        u32(0)  // m_UVInfo

        // ---- m_LocalAABB（解析器直接跳过 24 字节）----
        repeat(24) { o.write(0) }

        // ---- 尾部 ----
        i32(0)      // m_MeshUsageFlags
        i32(0)      // m_BakedConvexCollisionMesh（空）
        i32(0)      // m_BakedTriangleCollisionMesh（空）
        f32(0f); f32(0f)  // m_MeshMetrics（2018.2+）
        align4()
        // m_StreamData（2018.3+）：offset / size / path=""（内联数据）
        u32(0); u32(0); i32(0)

        return o.toByteArray()
    }

    /** 合成 quad（两三角形，带法线 UV） */
    internal fun quadSpec(name: String = "TestQuad"): MeshSpec = MeshSpec(
        name = name,
        positions = floatArrayOf(
            0f, 0f, 0f,
            1f, 0f, 0f,
            1f, 1f, 0f,
            0f, 1f, 0f
        ),
        normals = FloatArray(12) { if (it % 3 == 2) 1f else 0f },
        uvs = floatArrayOf(
            0f, 0f,
            1f, 0f,
            1f, 1f,
            0f, 1f
        ),
        indices = intArrayOf(0, 1, 2, 0, 2, 3)
    )

    /** 加载含单个 Mesh 的 bundle，返回解析出的 Mesh 实例 */
    private fun loadSingleMesh(spec: MeshSpec, fileName: String = "mesh.bundle"): Mesh {
        val objBytes = buildMeshObjectBytes(spec)
        val serialized = TestAssets.buildSerializedFile(objBytes, 43)
        val bundle = TestAssets.buildUnityFs(listOf("CAB-meshtest" to serialized))
        val mgr = AssetsManager()
        mgr.loadFile(fileName, bundle)
        assertEquals(1, mgr.assetsFileList.size, "应解析出 1 个资产文件")
        assertTrue(mgr.errors.isEmpty(), "Mesh 解析不应报错：${mgr.errors}")
        val mesh = mgr.assetsFileList[0].objects.firstOrNull { it is Mesh } as? Mesh
        assertNotNull(mesh, "应加载出 Mesh 对象")
        return mesh
    }

    // ============================ 测试 ============================

    @Test
    fun `obj 导出顶点法线UV与面完整`() {
        val mesh = loadSingleMesh(quadSpec())
        val obj = ObjExporter.write(mesh)

        val lines = obj.lines()
        assertEquals(4, lines.count { it.startsWith("v ") }, "应有 4 个顶点行")
        assertEquals(4, lines.count { it.startsWith("vn ") }, "应有 4 个法线行")
        assertEquals(4, lines.count { it.startsWith("vt ") }, "应有 4 个 UV 行")
        assertEquals(2, lines.count { it.startsWith("f ") }, "应有 2 个面行")
        // 面索引格式 v/vt/vn，1-based（(?m) 逐行匹配）
        assertTrue(Regex("(?m)^f 1/1/1 2/2/2 3/3/3$").containsMatchIn(obj), "第一个面应为 1/1/1 2/2/2 3/3/3")
        assertTrue(Regex("(?m)^f 1/1/1 3/3/3 4/4/4$").containsMatchIn(obj), "第二个面应为 1/1/1 3/3/3 4/4/4")
        // 顶点数值
        assertTrue(obj.contains("v 0.000000 0.000000 0.000000"), "应含原点顶点")
        assertTrue(obj.contains("v 1.000000 1.000000 0.000000"), "应含 (1,1,0) 顶点")
    }

    @Test
    fun `obj 导入支持分组负索引与四边形扇形三角化`() {
        val objText = """
            # 测试模型
            v 0.0 0.0 0.0
            v 2.0 0.0 0.0
            v 2.0 2.0 0.0
            v 0.0 2.0 0.0
            vt 0.0 0.0
            vt 1.0 0.0
            vt 1.0 1.0
            vt 0.0 1.0
            g part_a
            f 1/1 2/2 3/3
            g part_b
            f -4/-3 -2/-2 -3/-1
        """.trimIndent()

        val data = com.assetstudio.mobile.replace.ObjImporter.parse(objText)
        // 组合去重：part_a 产生 (v0,uv0)(v1,uv1)(v2,uv2)；
        // part_b 的 -4/-3 → (v0,uv1) 新增、-2/-2 → (v2,uv2) 复用、-3/-1 → (v1,uv0) 新增
        assertEquals(5, data.vertexCount, "5 个不同 v/vt 组合顶点")
        assertEquals(2, data.groups.size, "两个分组 → 两个子网格")
        assertEquals("part_a", data.groups[0].name)
        assertEquals(3, data.groups[0].indexCount)
        assertEquals(3, data.groups[1].indexCount)
        // 负索引语义：-4 = 顶点 0（末尾 4 - 4），配 -3 = uv1 → 输出顶点 3
        assertEquals(3, data.indices[3], "part_b 首点应为新组合顶点 3")
        // 该顶点位置 = v0 原点、UV = uv1 (1.0, 0.0)
        assertEquals(0.0f, data.positions[3 * 3], 1e-6f)
        assertEquals(1.0f, data.uvs!![3 * 2], 1e-6f, "负索引 -3 应取 uv1（U=1.0）")
        // UV 数值
        assertEquals(1.0f, data.uvs[2], 1e-6f, "顶点 1 的 U 应为 1.0")
        // 缺 vn → 自动平滑法线非零
        assertTrue(data.normals.any { abs(it) > 0.1f }, "应自动生成非零法线")
        // 包围盒
        assertEquals(2.0f, data.boundsMax[0], 1e-6f)
        assertEquals(0.0f, data.boundsMin[1], 1e-6f)
    }

    @Test
    fun `obj 导入四边形面扇形三角化为两个三角形`() {
        val objText = """
            v 0.0 0.0 0.0
            v 1.0 0.0 0.0
            v 1.0 1.0 0.0
            v 0.0 1.0 0.0
            vt 0.0 0.0
            vt 1.0 0.0
            vt 1.0 1.0
            vt 0.0 1.0
            f 1/1 2/2 3/3 4/4
        """.trimIndent()
        val data = com.assetstudio.mobile.replace.ObjImporter.parse(objText)
        assertEquals(4, data.vertexCount)
        assertEquals(6, data.indices.size, "四边形应扇形三角化为 2 个三角形（6 索引）")
        // 扇形：(0,1,2) + (0,2,3)
        assertArrayEquals(intArrayOf(0, 1, 2, 0, 2, 3), data.indices)
    }

    @Test
    fun `模型替换重打包后重新加载网格数据一致`() {
        // ---------- 1. 原始 quad ----------
        val mesh = loadSingleMesh(quadSpec("OrigQuad"))
        assertEquals(4, mesh.m_VertexCount)
        assertEquals(6, mesh.m_Indices.size)

        // ---------- 2. 用新 OBJ（3 顶点三角形）替换 ----------
        val newObjText = """
            o NewTri
            v 0.0 0.0 0.0
            v 5.0 0.0 0.0
            v 0.0 5.0 0.0
            vt 0.0 0.0
            vt 1.0 0.0
            vt 0.0 1.0
            f 1/1 2/2 3/3
        """.trimIndent()

        val repack = AssetReplacer.replaceMesh(mesh, newObjText)

        // ---------- 3. 重新加载（.bin 后缀场景）----------
        val mgr2 = AssetsManager()
        mgr2.loadFile("mesh_saved.bin", repack.bytes)
        assertEquals(1, mgr2.assetsFileList.size, "另存文件应重新解析出资产文件")
        assertTrue(mgr2.errors.isEmpty(), "重新解析不应报错：${mgr2.errors}")

        val mesh2 = mgr2.assetsFileList[0].objects.firstOrNull { it is Mesh } as? Mesh
        assertNotNull(mesh2, "应重新加载出 Mesh")

        // ---------- 4. 数据断言 ----------
        assertEquals("OrigQuad", mesh2.displayName, "名称应保留")
        assertEquals(3, mesh2.m_VertexCount, "顶点数应为新模型 3")
        assertEquals(3, mesh2.m_Indices.size, "索引数应为 1 个三角形")
        assertEquals(1, mesh2.m_SubMeshes.size, "无分组 OBJ → 单一子网格")
        assertEquals(3L, mesh2.m_SubMeshes[0].indexCount, "子网格索引数")

        // 顶点位置（OBJ 文本 %.6f 往返，1e-4 容差）
        val expected = floatArrayOf(0f, 0f, 0f, 5f, 0f, 0f, 0f, 5f, 0f)
        val vertices = mesh2.m_Vertices
        assertNotNull(vertices)
        assertEquals(expected.size, vertices.size)
        for (i in expected.indices) {
            assertTrue(abs(expected[i] - vertices[i]) < 1e-4f, "顶点[$i] ${vertices[i]} 应接近 ${expected[i]}")
        }

        // 索引
        assertEquals(0L, mesh2.m_Indices[0])
        assertEquals(1L, mesh2.m_Indices[1])
        assertEquals(2L, mesh2.m_Indices[2])

        // UV 通道保留（原网格有 UV 通道 → 新网格也有）
        val uv = mesh2.getUV(0)
        assertNotNull(uv, "UV 通道应保留")
        assertEquals(1.0f, uv[2], 1e-4f, "顶点 1 U=1.0")

        // 法线自动生成非零
        val normals = mesh2.m_Normals
        assertNotNull(normals)
        assertTrue(normals.any { abs(it) > 0.1f }, "法线应非零")
    }

    @Test
    fun `多分组obj替换生成多个子网格`() {
        val mesh = loadSingleMesh(quadSpec("MultiSub"))
        val newObjText = """
            o Split
            v 0.0 0.0 0.0
            v 1.0 0.0 0.0
            v 0.0 1.0 0.0
            v 2.0 0.0 0.0
            v 3.0 0.0 0.0
            v 2.0 1.0 0.0
            g left
            f 1 2 3
            g right
            f 4 5 6
        """.trimIndent()

        val repack = AssetReplacer.replaceMesh(mesh, newObjText)

        val mgr2 = AssetsManager()
        mgr2.loadFile("multi.bin", repack.bytes)
        val mesh2 = mgr2.assetsFileList[0].objects.firstOrNull { it is Mesh } as? Mesh
        assertNotNull(mesh2)
        assertEquals(2, mesh2.m_SubMeshes.size, "两个 g 分组 → 两个子网格")
        assertEquals(6, mesh2.m_Indices.size)
        assertEquals(6, mesh2.m_VertexCount)
        // 子网格区间：第一个 0..3，第二个 3..6
        assertEquals(0L, mesh2.m_SubMeshes[0].firstByte)
        assertEquals(3L, mesh2.m_SubMeshes[0].indexCount)
        assertEquals(6L, mesh2.m_SubMeshes[1].firstByte, "第二组索引起点应为字节 6")
        assertEquals(3L, mesh2.m_SubMeshes[1].indexCount)
    }

    @Test
    fun `无UV原网格替换后布局校验通过`() {
        // 原网格无 UV 通道（uvs=null）→ 替换时 UV 通道也不写
        val spec = MeshSpec(
            name = "NoUv",
            positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f),
            normals = FloatArray(12) { if (it % 3 == 2) 1f else 0f },
            uvs = null,
            indices = intArrayOf(0, 1, 2, 0, 2, 3)
        )
        val mesh = loadSingleMesh(spec)

        val newObjText = """
            o T
            v 0.0 0.0 0.0
            v 1.0 0.0 0.0
            v 0.5 1.0 0.0
            f 1 2 3
        """.trimIndent()

        val repack = AssetReplacer.replaceMesh(mesh, newObjText)
        val mgr2 = AssetsManager()
        mgr2.loadFile("nouv.bin", repack.bytes)
        val mesh2 = mgr2.assetsFileList[0].objects.firstOrNull { it is Mesh } as? Mesh
        assertNotNull(mesh2)
        assertEquals(3, mesh2.m_VertexCount)
        assertTrue(mesh2.getUV(0) == null, "原网格无 UV 通道 → 替换后也不应有 UV")
    }

    @Test
    fun `obj导出再导入回环数值保持`() {
        // 完整回环：合成 Mesh → OBJ 导出 → OBJ 导入 → 数据一致
        val mesh = loadSingleMesh(quadSpec("Loop"))
        val objText = ObjExporter.write(mesh)
        val data = com.assetstudio.mobile.replace.ObjImporter.parse(objText)

        assertEquals(mesh.m_VertexCount, data.vertexCount, "回环顶点数一致")
        assertEquals(mesh.m_Indices.size, data.indices.size, "回环索引数一致")
        for (i in data.indices.indices) {
            assertEquals(mesh.m_Indices[i].toInt(), data.indices[i], "索引[$i] 一致")
        }
        val srcVerts = mesh.m_Vertices!!
        for (i in srcVerts.indices) {
            assertTrue(abs(srcVerts[i] - data.positions[i]) < 1e-4f, "顶点[$i] 回环一致")
        }
    }
}

private fun assertArrayEquals(expected: IntArray, actual: IntArray) {
    assertEquals(expected.size, actual.size, "数组长度")
    for (i in expected.indices) {
        assertEquals(expected[i], actual[i], "元素[$i]")
    }
}

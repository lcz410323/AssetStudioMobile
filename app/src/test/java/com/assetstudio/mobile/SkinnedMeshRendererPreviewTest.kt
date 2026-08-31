package com.assetstudio.mobile

import com.assetstudio.mobile.core.classes.Mesh
import com.assetstudio.mobile.core.classes.SkinnedMeshRenderer
import com.assetstudio.mobile.core.manager.AssetsManager
import com.assetstudio.mobile.replace.AssetReplacer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/*
 * SkinnedMeshRenderer 预览/替换链路端到端测试（v1.7.1）：
 *
 * 详情页对渲染器组件的支持 = 解引用 m_Mesh PPtr → 复用 Mesh 预览与替换。
 * 本测试在真实解析环境（合成 SerializedFile → UnityFS bundle → AssetsManager）验证：
 *   1. SkinnedMeshRenderer 解析正确（骨骼/材质/混合形状字段）且 m_Mesh 可解引用到关联 Mesh
 *   2. 通过渲染器引用的 Mesh 执行替换后，重打包文件中：
 *      - Mesh 数据已更新（新顶点/索引）
 *      - SkinnedMeshRenderer 对象未被改动（骨骼数不变）
 *      - m_Mesh PPtr 仍指向同一 pathID（引用关系保持，游戏内绑定不丢）
 */

class SkinnedMeshRendererPreviewTest {

    private val meshHelper = MeshReplaceE2eTest()

    /**
     * 按 core/classes/SkinnedMeshRenderer.kt + Renderer.kt（2019.4 分支）逐字段构造对象字节。
     * 布局：GameObject PPtr → 渲染标志(8B) → 对齐 → 渲染层/优先级/lightmap →
     *   平铺偏移×2 → 材质数组 → 静态合批 → 探针锚点×3 → 排序 → 对齐 →
     *   quality/offscreen/skinNormals → 对齐 → m_Mesh PPtr → 骨骼数组 → 混合形状权重
     */
    private fun buildSmrObjectBytes(meshPathID: Long, boneCount: Int = 2): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        fun i32(v: Int) {
            o.write(v and 0xFF); o.write(v shr 8 and 0xFF)
            o.write(v shr 16 and 0xFF); o.write(v shr 24 and 0xFF)
        }
        fun i64(v: Long) { for (i in 0 until 8) o.write((v ushr (8 * i)).toInt()) }
        fun u8(v: Int) { o.write(v and 0xFF) }
        fun u16(v: Int) { o.write(v and 0xFF); o.write(v shr 8 and 0xFF) }
        fun u32(v: Long) = i32(v.toInt())
        fun f32(v: Float) = i32(java.lang.Float.floatToIntBits(v))
        /** PPtr：fileID(i32) + pathID(i64) */
        fun pptr(pathID: Long) { i32(0); i64(pathID) }
        fun align4() { while (o.size() % 4 != 0) o.write(0) }

        // ---- Component.m_GameObject（platform=Android ≠ NoTarget）----
        pptr(0L)

        // ---- Renderer 5.4+ 渲染标志（2019.4：含 DynamicOccludee / RayTracingMode）----
        u8(1)   // m_Enabled
        u8(1)   // m_CastShadows
        u8(1)   // m_ReceiveShadows
        u8(0)   // m_DynamicOccludee（2017.2+）
        u8(1)   // m_MotionVectors
        u8(1)   // m_LightProbeUsage
        u8(1)   // m_ReflectionProbeUsage
        u8(0)   // m_RayTracingMode（2019.3+）
        align4()

        u32(1)          // m_RenderingLayerMask（2018+）
        i32(0)          // m_RendererPriority（2018.3+）
        u16(0); u16(0)  // m_LightmapIndex / Dynamic
        repeat(4) { f32(0f) }  // m_LightmapTilingOffset
        repeat(4) { f32(0f) }  // m_LightmapTilingOffsetDynamic

        // m_Materials：1 个材质槽
        i32(1); pptr(0L)

        u16(0); u16(0)  // StaticBatchInfo（5.5+）
        pptr(0L)        // m_StaticBatchRoot
        pptr(0L)        // m_ProbeAnchor（5.4+）
        pptr(0L)        // m_LightProbeVolumeOverride（5.4+）
        i32(0)          // m_SortingLayerID（4.3+，非 4.3）
        u16(0)          // m_SortingOrder
        align4()

        // ---- SkinnedMeshRenderer 自身字段 ----
        i32(2)          // m_Quality
        u8(1)           // m_UpdateWhenOffscreen
        u8(0)           // m_SkinNormals
        align4()
        pptr(meshPathID)  // m_Mesh → 目标 Mesh
        i32(boneCount)    // m_Bones 数组
        repeat(boneCount) { pptr(0L) }
        i32(0)            // m_BlendShapeWeights（4.3+，空数组）
        return o.toByteArray()
    }

    /**
     * 构建含 Mesh(pathID=1, SkinQuad) + SkinnedMeshRenderer(pathID=2, 骨骼3/材质1) 场景的
     * AssetsManager。MCP 渲染器测试（McpRendererTest）复用此场景。
     */
    fun buildSmrAssetsManager(): AssetsManager {
        val meshBytes = meshHelper.buildMeshObjectBytes(meshHelper.quadSpec("SkinQuad"))
        val smrBytes = buildSmrObjectBytes(meshPathID = 1L, boneCount = 3)
        val serialized = TestAssets.buildSerializedFileMulti(
            listOf(
                43 to meshBytes,     // Mesh
                137 to smrBytes      // SkinnedMeshRenderer
            )
        )
        val bundle = TestAssets.buildUnityFs(listOf("CAB-smrtest" to serialized))
        val mgr = AssetsManager()
        mgr.loadFile("smr.bundle", bundle)
        assertEquals(1, mgr.assetsFileList.size, "应解析出 1 个资产文件")
        assertTrue(mgr.errors.isEmpty(), "解析不应报错：${mgr.errors}")
        return mgr
    }

    /** 加载含 Mesh(pathID=1) + SkinnedMeshRenderer(pathID=2) 的 bundle */
    private fun loadSmrScene(): Pair<Mesh, SkinnedMeshRenderer> {
        val mgr = buildSmrAssetsManager()

        val mesh = mgr.assetsFileList[0].objects.firstOrNull { it is Mesh } as? Mesh
        val smr = mgr.assetsFileList[0].objects.firstOrNull { it is SkinnedMeshRenderer } as? SkinnedMeshRenderer
        assertNotNull(mesh, "应加载出 Mesh")
        assertNotNull(smr, "应加载出 SkinnedMeshRenderer")
        return mesh to smr
    }

    @Test
    fun `渲染器字段解析正确且m_Mesh可解引用到关联网格`() {
        val (mesh, smr) = loadSmrScene()

        // 渲染器自身字段（详情页信息卡展示的数据源）
        assertEquals(3, smr.m_Bones.size, "骨骼数量")
        assertEquals(1, smr.m_Materials.size, "材质槽位")
        assertEquals(0, smr.m_BlendShapeWeights?.size, "混合形状权重（空数组）")

        // m_Mesh PPtr 解引用 → 详情页预览使用的网格
        val referenced = smr.m_Mesh.tryGet<Mesh>()
        assertNotNull(referenced, "m_Mesh 应解引用到 Mesh 对象")
        assertEquals(mesh.m_PathID, referenced.m_PathID, "解引用结果应为目标 Mesh 的 pathID")
        assertEquals(mesh.m_VertexCount, referenced.m_VertexCount, "网格数据一致")
        assertEquals("SkinQuad", referenced.displayName, "网格名称")

        // 同文件 PPtr：m_FileID == 0
        assertEquals(0, smr.m_Mesh.m_FileID, "同文件引用 m_FileID 应为 0")
    }

    @Test
    fun `通过渲染器引用替换网格后引用关系保持`() {
        val (_, smr) = loadSmrScene()

        // ---------- 模拟详情页替换入口：目标 = 渲染器引用的 Mesh（非 Mesh 资产本身） ----------
        val replaceTarget = smr.m_Mesh.tryGet<Mesh>()
        assertNotNull(replaceTarget, "替换目标应可解引用")
        assertEquals(4, replaceTarget.m_VertexCount, "原网格 4 顶点")

        val newObjText = """
            o NewSkinMesh
            v 0.0 0.0 0.0
            v 5.0 0.0 0.0
            v 0.0 5.0 0.0
            vt 0.0 0.0
            vt 1.0 0.0
            vt 0.0 1.0
            f 1/1 2/2 3/3
        """.trimIndent()

        val repack = AssetReplacer.replaceMesh(replaceTarget, newObjText)

        // ---------- 重新加载替换后的 bundle ----------
        val mgr2 = AssetsManager()
        mgr2.loadFile("smr_saved.bin", repack.bytes)
        assertEquals(1, mgr2.assetsFileList.size)
        assertTrue(mgr2.errors.isEmpty(), "重打包文件不应有解析错误：${mgr2.errors}")

        val mesh2 = mgr2.assetsFileList[0].objects.firstOrNull { it is Mesh } as? Mesh
        val smr2 = mgr2.assetsFileList[0].objects.firstOrNull { it is SkinnedMeshRenderer } as? SkinnedMeshRenderer
        assertNotNull(mesh2, "替换后应仍有 Mesh")
        assertNotNull(smr2, "替换后应仍有 SkinnedMeshRenderer")

        // 网格数据已更新
        assertEquals(3, mesh2.m_VertexCount, "新网格 3 顶点")
        assertEquals(3, mesh2.m_Indices.size, "新网格 1 个三角形")

        // 渲染器未被改动（未修改对象在重写中原样保留）
        assertEquals(3, smr2.m_Bones.size, "骨骼数量应保持不变")
        assertEquals(1, smr2.m_Materials.size, "材质槽位应保持不变")

        // 引用关系保持：PPtr 仍指向同一 pathID，且能解引用到新网格
        assertEquals(replaceTarget.m_PathID, smr2.m_Mesh.m_PathID, "m_Mesh pathID 应保持")
        val referenced2 = smr2.m_Mesh.tryGet<Mesh>()
        assertNotNull(referenced2, "替换后 m_Mesh 仍应可解引用")
        assertEquals(3, referenced2.m_VertexCount, "解引用到的应是替换后的新网格")
    }
}

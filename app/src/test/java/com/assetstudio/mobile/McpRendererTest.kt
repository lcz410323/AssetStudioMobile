package com.assetstudio.mobile

import com.assetstudio.mobile.mcp.AppMcpSource
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/*
 * MCP 渲染器组件支持测试（v1.7.2）：
 *
 * App UI 在 v1.7.1 已支持 SkinnedMeshRenderer/MeshFilter 预览与替换，
 * 本测试验证 MCP 数据源（AppMcpSource）具备同等能力：
 *   1. list_assets 可按 SkinnedMeshRenderer 类型过滤
 *   2. get_asset_detail 返回骨骼数/材质槽数/引用网格统计（referencedMesh）
 *   3. dump_render_asset 转储渲染器（网格引用/骨骼/材质槽可读文本）
 *   4. export_asset(obj) 经渲染器引用的网格导出 OBJ
 *   5. export_asset(txt) 导出渲染器转储文本
 *
 * 纯 JVM 环境（不触发 android.graphics 路径），场景复用 SkinnedMeshRendererPreviewTest。
 */

class McpRendererTest {

    private val helper = SkinnedMeshRendererPreviewTest()
    private lateinit var source: AppMcpSource
    private lateinit var exportDir: File

    @BeforeTest
    fun setUp() {
        val mgr = helper.buildSmrAssetsManager()
        exportDir = Files.createTempDirectory("mcp_smr_export").toFile()
        source = AppMcpSource(mgr, exportDir)
    }

    @AfterTest
    fun tearDown() {
        exportDir.deleteRecursively()
    }

    @Test
    fun `列表可按SkinnedMeshRenderer类型过滤`() {
        val page = source.listAssets("SkinnedMeshRenderer", null, 0, 50)
        assertEquals(1, page.total, "场景中恰有 1 个 SkinnedMeshRenderer")
        assertEquals("SkinnedMeshRenderer", page.items[0].type)
        assertEquals(2L, page.items[0].pathId, "pathID=2（Mesh=1, SMR=2）")

        // 全量列表应包含 Mesh 与 SMR 两类
        val all = source.listAssets(null, null, 0, 50)
        assertEquals(2, all.total)
        assertTrue(all.items.any { it.type == "Mesh" }, "应含 Mesh")
        assertTrue(all.items.any { it.type == "SkinnedMeshRenderer" }, "应含 SkinnedMeshRenderer")
    }

    @Test
    fun `详情返回骨骼材质与引用网格统计`() {
        val detail = source.assetDetail(2L, null)
        assertNotNull(detail, "pathID=2 应存在")
        assertEquals("SkinnedMeshRenderer", detail["type"])

        // 渲染器专属字段
        assertEquals(3, detail["boneCount"], "骨骼数量")
        assertEquals(1, detail["materialCount"], "材质槽位")
        assertEquals(0, detail["blendShapeWeightCount"], "混合形状权重（空数组）")

        // 原始 PPtr（跨文件引用定位用）
        @Suppress("UNCHECKED_CAST")
        val meshRef = detail["meshRef"] as Map<String, Any?>
        assertEquals(0, meshRef["fileId"], "同文件引用 fileId=0")
        assertEquals(1L, meshRef["pathId"])

        // 解引用后的网格统计
        @Suppress("UNCHECKED_CAST")
        val ref = detail["referencedMesh"] as Map<String, Any?>
        assertEquals(1L, ref["pathId"], "引用 pathID=1 的 Mesh")
        assertEquals("SkinQuad", ref["name"])
        assertEquals(4, ref["vertexCount"])
        assertEquals(1, ref["subMeshCount"])
        assertEquals(2, ref["triangleCount"])
    }

    @Test
    fun `转储渲染器为可读文本`() {
        val text = source.renderDump(2L, null)
        assertNotNull(text, "pathID=2 应可转储")

        assertTrue(text.contains("SkinnedMeshRenderer:"), "转储标题")
        // 网格引用段：解析出网格名与统计
        assertTrue(text.contains("--- 网格引用 ---"), "网格引用段")
        assertTrue(text.contains("「SkinQuad」"), "网格引用应解析出网格名")
        assertTrue(text.contains("顶点"), "网格统计")
        // 骨骼/材质槽段
        assertTrue(text.contains("--- 骨骼 (3) ---"), "骨骼段：3 根骨骼")
        assertTrue(text.contains("--- 材质槽 (1) ---"), "材质槽段：1 个槽位")
        // 混合形状权重段
        assertTrue(text.contains("--- 混合形状权重 (0) ---"), "混合形状权重段：空数组")
    }

    @Test
    fun `导出OBJ走渲染器引用的网格`() {
        val path = source.exportAsset(2L, null, "obj")
        assertTrue(path.endsWith(".obj"), "导出文件应为 .obj：$path")

        val text = File(path).readText(Charsets.UTF_8)
        assertTrue(text.contains("o SkinQuad"), "OBJ 对象名来自引用的 Mesh")
        assertTrue(text.contains("v "), "含顶点行")
        assertTrue(text.contains("f "), "含面行")
    }

    @Test
    fun `导出渲染器转储文本`() {
        val path = source.exportAsset(2L, null, "txt")
        assertTrue(path.endsWith(".txt"), "导出文件应为 .txt：$path")

        val text = File(path).readText(Charsets.UTF_8)
        assertTrue(text.contains("SkinnedMeshRenderer:"), "转储标题")
        assertTrue(text.contains("「SkinQuad」"), "网格引用解析")
        assertTrue(text.contains("--- 骨骼 (3) ---"), "骨骼段")
    }
}

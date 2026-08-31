package com.assetstudio.mobile

/*
 * MCP 核心纯 JVM 单元测试（不使用任何 Android 类）。
 * 运行: ./gradlew :app:testDebugUnitTest --tests "com.assetstudio.mobile.McpCoreTest"
 */

import com.assetstudio.mobile.mcp.Json
import com.assetstudio.mobile.mcp.McpAssetEntry
import com.assetstudio.mobile.mcp.McpAssetPage
import com.assetstudio.mobile.mcp.McpAssetSource
import com.assetstudio.mobile.mcp.McpFileSummary
import com.assetstudio.mobile.mcp.McpOutcome
import com.assetstudio.mobile.mcp.McpServerCore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpCoreTest {

    // ============================ 假数据源 ============================

    private class FakeSource : McpAssetSource {
        val files = listOf(McpFileSummary("game.bundle", "2021.3.15f1", 3))

        val assets = listOf(
            McpAssetEntry(100L, "TestTex", "Texture2D", 16384L, "game.bundle", "assets/textures/testtex.png"),
            McpAssetEntry(200L, "config", "TextAsset", 4096L, "game.bundle", "assets/config.txt"),
            McpAssetEntry(300L, "icon", "Sprite", 2048L, "game.bundle", "assets/ui/icon.png")
        )

        val configText = buildString {
            repeat(30) { append("line$it=value$it\n") } // ~330 字符，用于截断测试
        }

        override fun loadedFiles(): List<McpFileSummary> = files

        override fun listAssets(type: String?, keyword: String?, offset: Int, limit: Int): McpAssetPage {
            var filtered = assets
            if (!type.isNullOrEmpty()) filtered = filtered.filter { it.type.equals(type, ignoreCase = true) }
            if (!keyword.isNullOrEmpty()) {
                filtered = filtered.filter {
                    it.name.contains(keyword, ignoreCase = true) ||
                        it.containerPath.contains(keyword, ignoreCase = true)
                }
            }
            val total = filtered.size
            val from = offset.coerceIn(0, total)
            val to = (from + limit.coerceAtLeast(1)).coerceAtMost(total)
            return McpAssetPage(filtered.subList(from, to).toList(), total, offset, limit)
        }

        override fun searchAssets(keyword: String, limit: Int): List<McpAssetEntry> =
            listAssets(null, keyword, 0, limit).items

        override fun assetDetail(pathId: Long, fileName: String?): Map<String, Any?>? {
            val entry = assets.firstOrNull { it.pathId == pathId } ?: return null
            if (fileName != null && !entry.fileName.endsWith(fileName)) return null
            return linkedMapOf(
                "type" to entry.type,
                "name" to entry.name,
                "pathId" to entry.pathId,
                "byteSize" to entry.byteSize,
                "fileName" to entry.fileName,
                "container" to entry.containerPath
            )
        }

        override fun textContent(pathId: Long, fileName: String?): String? =
            if (pathId == 200L) configText else null

        override fun renderDump(pathId: Long, fileName: String?): String? =
            if (pathId == 100L) "Material: TestTex\nShader: Fake/Shader\n" else null

        override fun texturePreviewPng(pathId: Long, maxSize: Int): ByteArray? =
            if (pathId == 100L) byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) else null

        override fun textureStats(): Map<String, Any?> = linkedMapOf(
            "total" to 1,
            "inlineCount" to 1,
            "streamedCount" to 0,
            "totalDiskBytes" to 16384L,
            "totalVramBytes" to 4096L
        )

        override fun exportAsset(pathId: Long, fileName: String?, format: String): String =
            "/data/export/${assets.firstOrNull { it.pathId == pathId }?.name ?: "unknown"}.$format"
    }

    private val source = FakeSource()
    private val core = McpServerCore("test-server", "1.0-test") { source }

    // ============================ 调用辅助 ============================

    private fun call(raw: String): Map<String, Any?> {
        val outcome = core.handle(raw)
        assertTrue(outcome is McpOutcome.Reply, "期望 Reply，实际: $outcome")
        val body = (outcome as McpOutcome.Reply).body
        return Json.obj(Json.parse(body))!!
    }

    private fun callTool(name: String, arguments: String): Map<String, Any?> {
        val raw = """{"jsonrpc":"2.0","id":41,"method":"tools/call","params":{"name":"$name","arguments":$arguments}}"""
        return Json.obj(call(raw)["result"])!!
    }

    private fun resultOf(response: Map<String, Any?>): Map<String, Any?> = Json.obj(response["result"])!!

    private fun structuredOf(toolResult: Map<String, Any?>): Map<String, Any?> =
        Json.obj(toolResult["structuredContent"])!!

    private fun firstTextOf(toolResult: Map<String, Any?>): String {
        val content = Json.arr(toolResult["content"])!!
        return Json.str(Json.obj(content[0])!!["text"])!!
    }

    private fun idOf(response: Map<String, Any?>): Any? = response["id"]

    // ============================ JSON-RPC 基础 ============================

    @Test
    fun initializeReturnsProtocolVersionAndServerInfo() {
        val response = call(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"0"}}}"""
        )
        assertEquals(1L, idOf(response))
        val result = resultOf(response)
        assertEquals("2025-06-18", result["protocolVersion"])
        val serverInfo = Json.obj(result["serverInfo"])!!
        assertEquals("test-server", serverInfo["name"])
        assertEquals("1.0-test", serverInfo["version"])
        val capabilities = Json.obj(result["capabilities"])!!
        assertNotNull(capabilities["tools"])
    }

    @Test
    fun initializeNegotiatesSupportedClientVersion() {
        // 客户端请求旧版本（受支持）→ 按客户端版本协商
        val response = call(
            """{"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}"""
        )
        assertEquals("2024-11-05", resultOf(response)["protocolVersion"])

        // 客户端请求未知版本 → 回落到服务器默认版本
        val fallback = call(
            """{"jsonrpc":"2.0","id":3,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}"""
        )
        assertEquals("2025-06-18", resultOf(fallback)["protocolVersion"])
    }

    @Test
    fun pingReturnsEmptyObject() {
        val response = call("""{"jsonrpc":"2.0","id":10,"method":"ping"}""")
        val result = resultOf(response)
        assertTrue(result.isEmpty(), "ping 结果应为空对象，实际: $result")
    }

    @Test
    fun notificationsReturnAccepted() {
        val outcome = core.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertTrue(outcome is McpOutcome.Accepted, "notifications/initialized 应返回 Accepted")
        val outcome2 = core.handle("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":5}}""")
        assertTrue(outcome2 is McpOutcome.Accepted)
    }

    @Test
    fun clientResponseMessageIsAccepted() {
        // 有 result 无 method → 客户端 response 消息，不应答
        val outcome = core.handle("""{"jsonrpc":"2.0","id":7,"result":{"foo":"bar"}}""")
        assertTrue(outcome is McpOutcome.Accepted)
        // 有 error 无 method → 同样不应答
        val outcome2 = core.handle("""{"jsonrpc":"2.0","id":8,"error":{"code":-32000,"message":"cancelled"}}""")
        assertTrue(outcome2 is McpOutcome.Accepted)
    }

    @Test
    fun garbageJsonReturnsParseError() {
        for (bad in listOf("{not json", "", "[1,2,")) {
            val response = call(bad)
            val error = Json.obj(response["error"])!!
            assertEquals(-32700L, Json.long(error["code"]), "输入「$bad」应返回 -32700")
        }
        // 合法 JSON 但顶层不是对象（null / 数字 / 字符串 / 数组）→ INVALID_REQUEST
        for (nonObject in listOf("null", "123", "\"str\"", "[1,2]")) {
            val response = call(nonObject)
            val error = Json.obj(response["error"])!!
            assertEquals(-32600L, Json.long(error["code"]), "输入「$nonObject」应返回 -32600")
        }
    }

    @Test
    fun unknownMethodReturnsMethodNotFound() {
        val response = call("""{"jsonrpc":"2.0","id":11,"method":"resources/list"}""")
        val error = Json.obj(response["error"])!!
        assertEquals(-32601L, Json.long(error["code"]))
    }

    // ============================ tools/list ============================

    @Test
    fun toolsListHasExactlyNineToolsWithObjectSchema() {
        val response = call("""{"jsonrpc":"2.0","id":20,"method":"tools/list"}""")
        val result = resultOf(response)
        val tools = Json.arr(result["tools"])!!
        assertEquals(9, tools.size, "应恰好有 9 个工具")
        val names = ArrayList<String>()
        for (t in tools) {
            val tool = Json.obj(t)!!
            val name = Json.str(tool["name"])
            assertNotNull(name)
            assertNotNull(Json.str(tool["description"]), "工具 $name 缺少描述")
            val schema = Json.obj(tool["inputSchema"])!!
            assertEquals("object", schema["type"], "工具 $name 的 inputSchema.type 应为 object")
            assertNotNull(schema["properties"], "工具 $name 缺少 properties")
            names.add(name)
        }
        // 关键工具名均在列表中
        for (expected in listOf(
            "get_overview", "list_assets", "search_assets", "get_asset_detail",
            "read_text_asset", "get_texture_preview", "get_texture_stats",
            "dump_render_asset", "export_asset"
        )) {
            assertTrue(expected in names, "工具列表缺少 $expected")
        }
    }

    @Test
    fun searchAssetsRequiresKeyword() {
        val response = call("""{"jsonrpc":"2.0","id":21,"method":"tools/list"}""")
        val tools = Json.arr(resultOf(response)["tools"])!!
        val search = tools.first { Json.str(Json.obj(it)!!["name"]) == "search_assets" }
        val schema = Json.obj(Json.obj(search)!!["inputSchema"])!!
        val required = Json.arr(schema["required"])!!
        assertEquals(listOf("keyword"), required)
        // export_asset 的 format 枚举
        val export = tools.first { Json.str(Json.obj(it)!!["name"]) == "export_asset" }
        val exportSchema = Json.obj(Json.obj(export)!!["inputSchema"])!!
        val props = Json.obj(exportSchema["properties"])!!
        val format = Json.obj(props["format"])!!
        assertEquals(listOf("png", "txt", "obj", "raw"), Json.arr(format["enum"]))
    }

    // ============================ tools/call ============================

    @Test
    fun getOverviewReturnsFilesAndTypeDistribution() {
        val toolResult = callTool("get_overview", "{}")
        assertEquals(false, toolResult["isError"], "get_overview 不应报错")

        val text = firstTextOf(toolResult)
        assertTrue("game.bundle" in text, "总览文本应包含文件名 game.bundle:\n$text")
        assertTrue("Texture2D" in text, "总览文本应包含类型 Texture2D:\n$text")

        val structured = structuredOf(toolResult)
        assertEquals(3L, structured["assetCount"])
        assertEquals(1L, structured["fileCount"])
        assertEquals(16384L + 4096L + 2048L, Json.long(structured["totalBytes"]))
        val files = Json.arr(structured["files"])!!
        assertEquals("game.bundle", Json.str(Json.obj(files[0])!!["fileName"]))
    }

    @Test
    fun listAssetsFiltersTypeAndPaginates() {
        val all = callTool("list_assets", """{"offset":0,"limit":50}""")
        assertFalse(all["isError"] == true)
        assertEquals(3L, structuredOf(all)["total"])
        assertEquals(3, Json.arr(structuredOf(all)["items"])!!.size)

        val paged = callTool("list_assets", """{"offset":1,"limit":1}""")
        val items = Json.arr(structuredOf(paged)["items"])!!
        assertEquals(1, items.size)
        assertEquals(200L, Json.long(Json.obj(items[0])!!["pathId"]))

        val typed = callTool("list_assets", """{"type":"Texture2D"}""")
        val typedItems = Json.arr(structuredOf(typed)["items"])!!
        assertEquals(1, typedItems.size)
        assertEquals("TestTex", Json.str(Json.obj(typedItems[0])!!["name"]))

        val byContainer = callTool("list_assets", """{"keyword":"TEXTURES/"}""")
        val kwItems = Json.arr(structuredOf(byContainer)["items"])!!
        assertEquals(1, kwItems.size)
        assertEquals("TestTex", Json.str(Json.obj(kwItems[0])!!["name"]))
    }

    @Test
    fun searchAssetsReturnsMatches() {
        val result = callTool("search_assets", """{"keyword":"test"}""")
        assertFalse(result["isError"] == true)
        assertEquals(1L, structuredOf(result)["count"])
        val missing = callTool("search_assets", """{"keyword":"不存在的东西"}""")
        assertEquals(0L, structuredOf(missing)["count"])
        // 缺少必填参数 → in-band 错误
        val noKeyword = callTool("search_assets", "{}")
        assertTrue(noKeyword["isError"] == true, "缺少 keyword 应返回 isError=true")
    }

    @Test
    fun getAssetDetailReturnsStructuredFields() {
        val result = callTool("get_asset_detail", """{"pathId":100}""")
        assertFalse(result["isError"] == true)
        val structured = structuredOf(result)
        assertEquals("Texture2D", structured["type"])
        assertEquals("TestTex", structured["name"])
        assertEquals(100L, structured["pathId"])
        assertEquals("assets/textures/testtex.png", structured["container"])

        val wrongFile = callTool("get_asset_detail", """{"pathId":100,"fileName":"other.bundle"}""")
        assertTrue(wrongFile["isError"] == true)
    }

    @Test
    fun readTextAssetTruncatesByMaxChars() {
        val result = callTool("read_text_asset", """{"pathId":200,"maxChars":100}""")
        assertFalse(result["isError"] == true)
        val structured = structuredOf(result)
        assertEquals(true, structured["truncated"])
        assertEquals(100L, Json.long(structured["maxChars"]))
        assertEquals(source.configText.length.toLong(), Json.long(structured["length"]))
        val content = Json.str(structured["content"])!!
        assertEquals(100, content.length)
        assertEquals(source.configText.take(100), content)
    }

    @Test
    fun readTextAssetOutOfRangePathIdIsError() {
        val result = callTool("read_text_asset", """{"pathId":99999}""")
        assertTrue(result["isError"] == true, "越界 pathId 应返回 isError=true")
        val text = firstTextOf(result)
        assertTrue("99999" in text, "错误文本应包含越界 pathId: $text")
    }

    @Test
    fun getTexturePreviewReturnsImagePlusTextContent() {
        val result = callTool("get_texture_preview", """{"pathId":100,"maxSize":256}""")
        assertFalse(result["isError"] == true)
        val content = Json.arr(result["content"])!!
        assertEquals(2, content.size, "预览应返回 image + text 两个 content")
        val image = Json.obj(content[0])!!
        assertEquals("image", image["type"])
        assertEquals("image/png", image["mimeType"])
        val data = Json.str(image["data"])!!
        assertEquals(
            java.util.Base64.getEncoder().encodeToString(source.texturePreviewPng(100, 256)!!),
            data,
            "base64 数据应与源数据一致（NO_WRAP）"
        )
        assertEquals(256L, structuredOf(result)["maxSize"])

        val failed = callTool("get_texture_preview", """{"pathId":300}""")
        assertTrue(failed["isError"] == true)
    }

    @Test
    fun getTextureStatsReturnsStructuredMap() {
        val result = callTool("get_texture_stats", "{}")
        assertFalse(result["isError"] == true)
        assertEquals(1L, structuredOf(result)["total"])
        assertTrue("Texture2D" in firstTextOf(result) || "total" in firstTextOf(result))
    }

    @Test
    fun dumpRenderAssetDumpsMaterialOrShader() {
        val ok = callTool("dump_render_asset", """{"pathId":100}""")
        assertFalse(ok["isError"] == true)
        assertEquals("Material: TestTex", firstTextOf(ok).substringBefore('\n'))
        assertEquals("Material: TestTex", Json.str(structuredOf(ok)["content"])!!.substringBefore('\n'))

        val notRender = callTool("dump_render_asset", """{"pathId":300}""")
        assertTrue(notRender["isError"] == true)
        val message = firstTextOf(notRender)
        assertEquals("pathId=300 不是 Material / Shader（或不存在）", message)
    }

    @Test
    fun exportAssetReturnsPath() {
        val result = callTool("export_asset", """{"pathId":100,"format":"png"}""")
        assertFalse(result["isError"] == true)
        val structured = structuredOf(result)
        assertEquals("png", structured["format"])
        assertTrue(Json.str(structured["path"])!!.endsWith("TestTex.png"))

        val badFormat = callTool("export_asset", """{"pathId":100,"format":"exe"}""")
        assertTrue(badFormat["isError"] == true)
    }

    @Test
    fun unknownToolNameIsError() {
        val result = callTool("no_such_tool", "{}")
        assertTrue(result["isError"] == true)
        assertTrue("no_such_tool" in firstTextOf(result))
    }

    // ============================ Json 辅助 ============================

    @Test
    fun jsonParseAndWriteRoundTrip() {
        val original = """{"a":1,"b":[true,false,null],"c":"中文\\n\"引号\"","d":-2.5,"e":1e3}"""
        val parsed = Json.parse(original)
        val map = Json.obj(parsed)!!
        assertEquals(1L, Json.long(map["a"]))
        assertEquals(3, Json.arr(map["b"])!!.size)
        assertEquals("中文\\n\"引号\"", Json.str(map["c"]))
        assertEquals(-2.5, Json.double(map["d"])!!)
        // 序列化可再次解析
        val reparsed = Json.obj(Json.parse(Json.write(map)))!!
        assertEquals(Json.str(map["c"]), Json.str(reparsed["c"]))
        // Base64 回环
        val bytes = byteArrayOf(0, 1, 2, 250.toByte(), 251.toByte(), 252.toByte(), 253.toByte(), 254.toByte(), 255.toByte())
        val encoded = Json.base64Encode(bytes)
        assertTrue(encoded.indexOf('\n') < 0 && encoded.indexOf('\r') < 0, "应为 NO_WRAP 风格")
        assertTrue(bytes.contentEquals(Json.base64Decode(encoded)))
    }
}

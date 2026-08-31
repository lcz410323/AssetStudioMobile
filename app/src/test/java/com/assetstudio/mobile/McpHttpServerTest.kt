package com.assetstudio.mobile

/*
 * MCP HTTP 服务器纯 JVM 单元测试（不使用任何 Android 类）。
 * 运行: ./gradlew :app:testDebugUnitTest --tests "com.assetstudio.mobile.McpHttpServerTest"
 */

import com.assetstudio.mobile.mcp.McpAssetEntry
import com.assetstudio.mobile.mcp.McpAssetPage
import com.assetstudio.mobile.mcp.McpAssetSource
import com.assetstudio.mobile.mcp.McpFileSummary
import com.assetstudio.mobile.mcp.McpHttpServer
import com.assetstudio.mobile.mcp.McpManager
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpHttpServerTest {

    // ============================ 空数据源 ============================

    private object EmptySource : McpAssetSource {
        override fun loadedFiles(): List<McpFileSummary> = emptyList()
        override fun listAssets(type: String?, keyword: String?, offset: Int, limit: Int): McpAssetPage =
            McpAssetPage(emptyList(), 0, offset, limit)
        override fun searchAssets(keyword: String, limit: Int): List<McpAssetEntry> = emptyList()
        override fun assetDetail(pathId: Long, fileName: String?): Map<String, Any?>? = null
        override fun textContent(pathId: Long, fileName: String?): String? = null
        override fun renderDump(pathId: Long, fileName: String?): String? = null
        override fun texturePreviewPng(pathId: Long, maxSize: Int): ByteArray? = null
        override fun textureStats(): Map<String, Any?> = emptyMap()
        override fun exportAsset(pathId: Long, fileName: String?, format: String): String =
            throw IllegalArgumentException("no asset")
    }

    // ============================ HTTP 辅助 ============================

    /** 在 18xxx 范围内随机挑一个端口并启动服务器（端口冲突时重试） */
    private fun startOnRandomPort(): Pair<McpHttpServer, Int> {
        var lastError: Exception? = null
        repeat(10) {
            val port = 18000 + Random.nextInt(1000)
            val server = McpHttpServer("test-http", "1.0-test", port) { EmptySource }
            try {
                server.start()
                return server to server.port()
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalStateException("无法绑定随机端口: $lastError")
    }

    private fun httpPost(port: Int, body: String): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:$port/mcp").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Mcp-Session-Id", "test-session-id") // 服务端应忽略
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            code to text
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGet(port: Int): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:$port/mcp").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            code to text
        } finally {
            conn.disconnect()
        }
    }

    private val initializeRequest =
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"junit","version":"0"}}}"""

    private val notificationRequest =
        """{"jsonrpc":"2.0","method":"notifications/initialized"}"""

    // ============================ 用例 ============================

    @Test
    fun postInitializeReturns200WithResult() {
        val (server, port) = startOnRandomPort()
        try {
            assertTrue(server.isRunning())
            assertEquals(port, server.port())

            val (code, body) = httpPost(port, initializeRequest)
            assertEquals(200, code, "POST initialize 应返回 200，body=$body")
            assertTrue("\"result\"" in body, "响应体应包含 result 字段: $body")
            assertTrue("\"serverInfo\"" in body, "initialize 响应应包含 serverInfo: $body")
            assertTrue("\"2025-06-18\"" in body, "initialize 响应应协商出协议版本: $body")
        } finally {
            server.stop()
        }
        assertFalse(server.isRunning(), "stop() 之后应不再运行")
    }

    @Test
    fun postNotificationReturns202() {
        val (server, port) = startOnRandomPort()
        try {
            val (code, body) = httpPost(port, notificationRequest)
            assertEquals(202, code, "POST notification 应返回 202")
            assertEquals("", body, "202 响应不应有 body")
        } finally {
            server.stop()
        }
    }

    @Test
    fun postClientResponseMessageReturns202() {
        val (server, port) = startOnRandomPort()
        try {
            val (code, _) = httpPost(port, """{"jsonrpc":"2.0","id":9,"result":{"ok":true}}""")
            assertEquals(202, code, "客户端 response 消息应返回 202")
        } finally {
            server.stop()
        }
    }

    @Test
    fun garbageBodyReturns200WithParseError() {
        val (server, port) = startOnRandomPort()
        try {
            val (code, body) = httpPost(port, "this is not json {{{")
            assertEquals(200, code, "垃圾 body 仍应返回 200（JSON-RPC 错误体）")
            assertTrue("-32700" in body, "应返回 PARSE_ERROR(-32700): $body")
        } finally {
            server.stop()
        }
    }

    @Test
    fun getReturns405() {
        val (server, port) = startOnRandomPort()
        try {
            val (code, body) = httpGet(port)
            assertEquals(405, code, "GET 应返回 405")
            assertTrue(body.isEmpty() || "-32600" in body || "POST" in body, "405 响应体应为空或 JSON-RPC 错误: $body")
        } finally {
            server.stop()
        }
    }

    @Test
    fun mcpManagerStartStopLifecycle() {
        McpManager.sourceProvider = { EmptySource }
        var port = 18000 + Random.nextInt(1000)
        try {
            var started = McpManager.startServer(port)
            // 端口偶发冲突时换一个端口重试
            repeat(5) {
                if (!started) {
                    port = 18000 + Random.nextInt(1000)
                    started = McpManager.startServer(port)
                }
            }
            assertTrue(started, "McpManager.startServer 应成功")
            assertTrue(McpManager.status().contains(port.toString()), "status 应包含端口: ${McpManager.status()}")
            assertTrue(McpManager.status().contains("运行"), "status 应包含运行状态: ${McpManager.status()}")

            val (code, body) = httpPost(port, initializeRequest)
            assertEquals(200, code)
            assertTrue("\"result\"" in body)

            // 注入 EmptySource：get_overview 正常返回（assetCount=0）
            val (toolCode, toolBody) = httpPost(
                port,
                """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_overview","arguments":{}}}"""
            )
            assertEquals(200, toolCode)
            assertTrue("\"isError\":false" in toolBody.replace(" ", ""), "EmptySource 下 get_overview 应为 isError=false: $toolBody")

            // 撤销注入（sourceProvider=null）：工具调用返回 in-band 错误
            McpManager.sourceProvider = null
            val (noSourceCode, noSourceBody) = httpPost(
                port,
                """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_overview","arguments":{}}}"""
            )
            assertEquals(200, noSourceCode)
            assertTrue(
                "\"isError\":true" in noSourceBody.replace(" ", ""),
                "未注入数据源时 get_overview 应为 isError=true: $noSourceBody"
            )
            assertTrue("尚未加载" in noSourceBody)
        } finally {
            McpManager.stopServer()
            McpManager.sourceProvider = null
        }
        assertTrue(McpManager.status().contains("未运行"), "stopServer 后状态应为未运行: ${McpManager.status()}")
    }
}

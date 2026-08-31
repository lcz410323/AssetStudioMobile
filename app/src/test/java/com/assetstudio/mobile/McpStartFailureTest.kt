package com.assetstudio.mobile

import com.assetstudio.mobile.mcp.McpManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/*
 * MCP 启动失败路径回归测试（对应"图二修复 mcp"）：
 * 修复前 startMcp 忽略 startServer() 返回值，端口被占用时
 * 仍提示"已启动"且把"未运行"写进状态导致 UI 错乱；
 * 修复后失败原因记录在 McpManager.lastStartError，且成功时被清空。
 */
class McpStartFailureTest {

    @Test
    fun `端口被占用时启动失败并记录原因`() {
        ServerSocket(0).use { blocker ->
            val occupiedPort = blocker.localPort
            McpManager.sourceProvider = null
            try {
                val ok = McpManager.startServer(occupiedPort)
                assertFalse("端口被占用时 startServer 应返回 false", ok)
                val reason = McpManager.lastStartError
                assertNotNull("失败原因应被记录", reason)
                assertTrue("原因应提示端口占用: $reason", reason?.contains("占用") == true)
                // 失败后服务器确实未运行
                assertTrue(McpManager.status().contains("未运行"))
            } finally {
                McpManager.stopServer()
            }
        }
    }

    @Test
    fun `成功启动后失败原因被清空`() {
        McpManager.sourceProvider = null
        try {
            // 先制造一次失败（占用端口启动），确认失败原因被记录
            ServerSocket(0).use { blocker ->
                assertFalse(McpManager.startServer(blocker.localPort))
                assertNotNull("失败原因应被记录", McpManager.lastStartError)
            }
            // 再成功启动（随机端口，偶发冲突则换端口重试）：失败原因应被清空
            var started = false
            repeat(8) {
                if (!started) {
                    started = McpManager.startServer(19000 + (Math.random() * 1000).toInt())
                }
            }
            assertTrue("空闲端口启动应成功", started)
            assertNull("成功启动后 lastStartError 应被清空", McpManager.lastStartError)
        } finally {
            McpManager.stopServer()
        }
        assertEquals("MCP 服务器未运行", McpManager.status())
    }
}

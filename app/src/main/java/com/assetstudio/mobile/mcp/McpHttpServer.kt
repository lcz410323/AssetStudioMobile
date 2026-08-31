package com.assetstudio.mobile.mcp

/*
 * 基于 java.net.ServerSocket 的最小 HTTP/1.1 服务器（纯 Kotlin，无第三方库，不依赖 android.*）。
 *
 * 传输层约定（Streamable HTTP 风格的最小子集）：
 * - 仅处理 POST：读取 Content-Length 指定的 body，交给 McpServerCore.handle；
 *     Reply    → 200 application/json（带响应体）
 *     Accepted → 202（无响应体）
 * - 非 POST（GET/PUT/DELETE/...）→ 405 + JSON-RPC 错误体（Allow: POST）
 * - Mcp-Session-Id 请求头按"忽略"处理（无会话管理，每请求独立）
 * - keep-alive 未启用：每响应后按 connection: close 关闭连接（简化实现）
 */

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

class McpHttpServer(
    private val serverName: String,
    private val serverVersion: String,
    private val port: Int,
    private val sourceProvider: () -> McpAssetSource?
) {
    companion object {
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val MAX_BODY_BYTES = 64 * 1024 * 1024
    }

    private val core: McpServerCore = McpServerCore(serverName, serverVersion, sourceProvider)

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var running: Boolean = false

    // ============================ 生命周期 ============================

    @Synchronized
    fun start() {
        if (running) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port))
        serverSocket = socket
        running = true
        val thread = Thread({ acceptLoop(socket) }, "McpHttpServer-accept-${socket.localPort}")
        thread.isDaemon = true
        acceptThread = thread
        thread.start()
    }

    @Synchronized
    fun stop() {
        running = false
        val socket = serverSocket
        serverSocket = null
        acceptThread = null
        if (socket != null) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    fun isRunning(): Boolean = running && serverSocket?.isClosed == false

    /** 实际监听端口（port=0 时返回系统分配的端口） */
    fun port(): Int = serverSocket?.localPort ?: port

    // ============================ 连接处理 ============================

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            val client = try {
                server.accept()
            } catch (e: Exception) {
                if (running) {
                    // accept 异常（如瞬时资源不足）稍作等待后继续
                    try {
                        Thread.sleep(50)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }
                break
            }
            val worker = Thread({ handleConnection(client) }, "McpHttpServer-client")
            worker.isDaemon = true
            worker.start()
        }
    }

    private fun handleConnection(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = SOCKET_TIMEOUT_MS
                s.tcpNoDelay = true
                val reader = ByteLineReader(s.getInputStream())
                val output = s.getOutputStream()
                while (running) {
                    val request = readHttpRequest(reader) ?: break
                    val closeAfter = respond(output, request)
                    if (closeAfter) break
                }
            }
        } catch (_: Exception) {
            // 连接异常（超时/重置/半关闭等）：直接结束该连接
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    // ============================ 请求 / 响应 ============================

    private fun respond(output: OutputStream, request: HttpRequest): Boolean {
        val body: ByteArray?
        val status: Int
        val reason: String
        val contentType: String?
        val extraHeaders: List<String>
        if (request.method == "POST") {
            val raw = String(request.body, StandardCharsets.UTF_8)
            when (val outcome = core.handle(raw)) {
                is McpOutcome.Reply -> {
                    status = 200
                    reason = "OK"
                    contentType = "application/json"
                    body = outcome.body.toByteArray(StandardCharsets.UTF_8)
                }
                is McpOutcome.Accepted -> {
                    status = 202
                    reason = "Accepted"
                    contentType = null
                    body = null
                }
            }
            extraHeaders = emptyList()
        } else {
            status = 405
            reason = "Method Not Allowed"
            contentType = "application/json"
            body = Json.rpcError(
                null,
                McpProtocol.INVALID_REQUEST,
                "MCP 端点仅支持 POST（收到 ${request.method}）"
            ).toByteArray(StandardCharsets.UTF_8)
            extraHeaders = listOf("Allow: POST")
        }
        writeResponse(output, status, reason, contentType, body, extraHeaders)
        // 简化实现：不启用 keep-alive，每响应后关闭连接
        return true
    }

    private fun writeResponse(
        output: OutputStream,
        status: Int,
        reason: String,
        contentType: String?,
        body: ByteArray?,
        extraHeaders: List<String> = emptyList()
    ) {
        val sb = StringBuilder(256)
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        if (contentType != null) {
            sb.append("Content-Type: ").append(contentType).append("\r\n")
        }
        sb.append("Content-Length: ").append(body?.size ?: 0).append("\r\n")
        for (header in extraHeaders) {
            sb.append(header).append("\r\n")
        }
        sb.append("Connection: close\r\n")
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(StandardCharsets.ISO_8859_1))
        if (body != null && body.isNotEmpty()) {
            output.write(body)
        }
        output.flush()
    }

    // ============================ HTTP 解析 ============================

    private class HttpRequest(
        val method: String,
        val path: String,
        val version: String,
        val headers: Map<String, String>,
        val body: ByteArray
    )

    private fun readHttpRequest(reader: ByteLineReader): HttpRequest? {
        // 请求行（容忍前置空行）
        var requestLine = reader.readLine() ?: return null
        while (requestLine.isEmpty()) {
            requestLine = reader.readLine() ?: return null
        }
        val parts = requestLine.trim().split(" ")
        if (parts.size < 3) throw IOException("非法请求行: $requestLine")
        val method = parts[0].uppercase()
        val path = parts[1]
        val version = parts[2]

        // 头部（键统一小写）
        val headers = HashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val index = line.indexOf(':')
            if (index > 0) {
                headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
            }
        }

        // body：仅按 Content-Length 读取（不支持 chunked）
        var contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength < 0) contentLength = 0
        if (contentLength > MAX_BODY_BYTES) throw IOException("请求体过大: $contentLength 字节")
        val body = reader.readBytes(contentLength)
        return HttpRequest(method, path, version, headers, body)
    }

    /** 字节级读取器：按行解析头部（Latin-1），body 以原始字节返回 */
    private class ByteLineReader(private val input: InputStream) {
        private val buffer = ByteArray(8192)
        private var position = 0
        private var count = 0
        private var exhausted = false

        fun readByte(): Int {
            if (position >= count) {
                if (exhausted) return -1
                val n = try {
                    input.read(buffer)
                } catch (e: IOException) {
                    -1
                }
                if (n < 0) {
                    exhausted = true
                    return -1
                }
                count = n
                position = 0
            }
            return buffer[position++].toInt() and 0xFF
        }

        fun readLine(): String? {
            val sb = StringBuilder(80)
            while (true) {
                val b = readByte()
                if (b < 0) {
                    return if (sb.isEmpty()) null else sb.toString()
                }
                if (b == '\n'.code) {
                    if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') {
                        sb.setLength(sb.length - 1)
                    }
                    return sb.toString()
                }
                sb.append(b.toChar())
            }
        }

        fun readBytes(n: Int): ByteArray {
            if (n <= 0) return ByteArray(0)
            val out = ByteArray(n)
            var read = 0
            while (read < n) {
                val b = readByte()
                if (b < 0) break
                out[read++] = b.toByte()
            }
            return if (read == n) out else out.copyOf(read)
        }
    }
}

package com.assetstudio.mobile.mcp

/*
 * MCP 服务器管理器（单例）。
 *
 * 宿主（App/MainViewModel）通过 sourceProvider 注入资产数据源后，
 * 调用 startServer(port) 即可在本机端口上对外提供 MCP 服务
 * （默认端口 8765，供局域网内的 AI 客户端连接）。
 *
 * 该文件允许依赖 Android（当前实现未用到 android.*，保持纯 Kotlin 以便测试）。
 */

object McpManager {

    /** 默认监听端口 */
    const val DEFAULT_PORT = 8765

    private const val SERVER_NAME = "AssetStudioMobile"
    private const val SERVER_VERSION = "1.3.4"

    /**
     * 宿主注入的数据源提供者：返回当前已加载资产的 AppMcpSource，
     * 未加载任何文件时应返回 null（工具调用会得到"尚未加载任何资源文件"错误）。
     */
    @Volatile
    var sourceProvider: (() -> McpAssetSource)? = null

    /** 懒创建的 HTTP 服务器实例 */
    @Volatile
    private var server: McpHttpServer? = null

    /** 最近一次 startServer 失败的原因（成功启动时置空），供 UI 展示 */
    @Volatile
    var lastStartError: String? = null
        private set

    /**
     * 启动 MCP HTTP 服务器（已在运行且端口一致时为幂等操作）。
     *
     * 注意：内部会执行 ServerSocket.bind（网络操作），Android 主线程调用会被
     * 系统拦截抛 NetworkOnMainThreadException——宿主必须在后台线程调用。
     *
     * @return 是否成功启动
     */
    @Synchronized
    fun startServer(port: Int = DEFAULT_PORT): Boolean {
        lastStartError = null
        val current = server
        if (current != null && current.isRunning() && current.port() == port) {
            return true
        }
        current?.stop()
        val provider: () -> McpAssetSource? = { sourceProvider?.invoke() }
        val httpServer = McpHttpServer(SERVER_NAME, SERVER_VERSION, port, provider)
        return try {
            httpServer.start()
            server = httpServer
            true
        } catch (e: Exception) {
            // 不能 import android.*（本文件保持纯 Kotlin 以便 JVM 测试），
            // 用类名识别主线程网络拦截
            lastStartError = when {
                e is java.net.BindException -> "端口 $port 已被占用"
                e.javaClass.simpleName == "NetworkOnMainThreadException" ->
                    "主线程网络操作被系统拦截（应后台线程启动）"
                // EPERM：应用未声明 INTERNET 权限时创建套接字被内核拒绝
                e.message?.contains("EPERM") == true ->
                    "系统拒绝创建网络套接字（EPERM）：应用缺少 INTERNET 权限"
                else -> e.message ?: e.javaClass.simpleName
            }
            try {
                httpServer.stop()
            } catch (_: Exception) {
            }
            false
        }
    }

    /** 停止 MCP HTTP 服务器 */
    @Synchronized
    fun stopServer() {
        server?.stop()
        server = null
    }

    /** 运行状态描述（含端口），供 UI 展示 */
    fun status(): String {
        val current = server
        return if (current != null && current.isRunning()) {
            "MCP 服务器运行中（端口 ${current.port()}）"
        } else {
            "MCP 服务器未运行"
        }
    }
}

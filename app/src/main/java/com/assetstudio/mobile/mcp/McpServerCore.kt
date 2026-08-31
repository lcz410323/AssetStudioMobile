package com.assetstudio.mobile.mcp

/*
 * MCP（Model Context Protocol）服务器核心。
 *
 * 纯 Kotlin/JVM 实现，不 import 任何 android.* 类，可直接在纯 JVM 单元测试中运行：
 * - McpProtocol   协议版本常量与 JSON-RPC 2.0 错误码
 * - McpOutcome    handle() 的处理结果（Reply=需要应答 / Accepted=无需应答）
 * - McpAssetEntry / McpAssetPage / McpFileSummary  数据源与核心层之间的数据模型
 * - McpAssetSource 宿主注入的资产数据源接口（由 AppMcpSource 基于 AssetsManager 实现）
 * - Json          自实现的轻量 JSON 解析/序列化 + JSON-RPC 响应构建辅助（无第三方库）
 * - McpServerCore JSON-RPC 2.0 分发器：
 *     initialize（协议版本协商）/ notifications 前缀通知（不应答）/ ping / tools/list / tools/call
 *     未知方法 → METHOD_NOT_FOUND，JSON 解析失败 → PARSE_ERROR，
 *     客户端 response 消息（有 result 无 method）→ Accepted。
 */

import java.util.Locale

// ============================ 协议常量 ============================

object McpProtocol {
    /** 服务器默认声明的协议版本 */
    const val VERSION = "2025-06-18"

    /** 支持的协议版本集合（initialize 时与客户端协商） */
    val SUPPORTED = setOf("2025-06-18", "2025-03-26", "2024-11-05")

    // JSON-RPC 2.0 错误码
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
}

// ============================ 处理结果 ============================

/** McpServerCore.handle 的输出：HTTP 层据此决定 200(带 body) 或 202(无 body) */
sealed class McpOutcome {
    /** 需要返回给客户端的 JSON-RPC 响应体 */
    data class Reply(val body: String) : McpOutcome()

    /** 无需应答（通知消息或客户端 response 消息），HTTP 层应答 202 */
    data object Accepted : McpOutcome()
}

// ============================ 数据模型 ============================

/** 资源条目（列表/搜索结果的最小单元） */
data class McpAssetEntry(
    val pathId: Long,
    val name: String,
    val type: String,
    val byteSize: Long,
    val fileName: String,
    val containerPath: String
)

/** 分页结果 */
data class McpAssetPage(
    val items: List<McpAssetEntry>,
    val total: Int,
    val offset: Int,
    val limit: Int
)

/** 已加载文件摘要 */
data class McpFileSummary(
    val fileName: String,
    val unityVersion: String,
    val objectCount: Int
)

// ============================ 数据源接口 ============================

/**
 * 宿主注入的资产数据源。核心层只依赖本接口，
 * Android 侧由 AppMcpSource（基于 core.manager.AssetsManager）实现。
 */
interface McpAssetSource {
    fun loadedFiles(): List<McpFileSummary>
    fun listAssets(type: String?, keyword: String?, offset: Int, limit: Int): McpAssetPage
    fun searchAssets(keyword: String, limit: Int): List<McpAssetEntry>
    fun assetDetail(pathId: Long, fileName: String?): Map<String, Any?>?
    fun textContent(pathId: Long, fileName: String?): String?
    fun renderDump(pathId: Long, fileName: String?): String?
    fun texturePreviewPng(pathId: Long, maxSize: Int): ByteArray?
    fun textureStats(): Map<String, Any?>
    fun exportAsset(pathId: Long, fileName: String?, format: String): String
}

// ============================ 轻量 JSON ============================

/**
 * 自实现的轻量 JSON 解析/序列化（无第三方库）。
 * 解析结果映射：object→LinkedHashMap<String,Any?>、array→ArrayList<Any?>、
 * string→String、整数→Long、小数/指数→Double、true/false→Boolean、null→null。
 */
object Json {

    class JsonException(message: String) : Exception(message)

    // ---------------------------- 解析 ----------------------------

    fun parse(str: String): Any? {
        val parser = Parser(str)
        parser.skipWhitespace()
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) throw JsonException("JSON 末尾存在多余字符（偏移 ${parser.i}）")
        return value
    }

    private class Parser(val s: String) {
        var i = 0

        fun atEnd(): Boolean = i >= s.length

        fun skipWhitespace() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++ else break
            }
        }

        fun parseValue(): Any? {
            if (atEnd()) throw JsonException("JSON 意外结束")
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else -> parseNumber()
            }
        }

        private fun parseLiteral(word: String, value: Any?): Any? {
            if (i + word.length > s.length || !s.startsWith(word, i)) {
                throw JsonException("非法字面量（偏移 $i）")
            }
            i += word.length
            return value
        }

        private fun parseObject(): LinkedHashMap<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                i++
                return map
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw JsonException("对象键必须是字符串（偏移 $i）")
                val key = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                map[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return map
                    }
                    else -> throw JsonException("对象中期待 ',' 或 '}'（偏移 $i）")
                }
            }
        }

        private fun parseArray(): ArrayList<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                i++
                return list
            }
            while (true) {
                skipWhitespace()
                list.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return list
                    }
                    else -> throw JsonException("数组中期待 ',' 或 ']'（偏移 $i）")
                }
            }
        }

        private fun peek(): Char {
            if (atEnd()) throw JsonException("JSON 意外结束")
            return s[i]
        }

        private fun expect(c: Char) {
            if (atEnd() || s[i] != c) throw JsonException("期待 '$c'（偏移 $i）")
            i++
        }

        fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonException("字符串未闭合")
                when (val c = s[i]) {
                    '"' -> {
                        i++
                        return sb.toString()
                    }
                    '\\' -> {
                        i++
                        if (atEnd()) throw JsonException("非法转义（偏移 $i）")
                        when (val e = s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 >= s.length) throw JsonException("非法 \\u 转义（偏移 $i）")
                                val hex = s.substring(i + 1, i + 5)
                                val code = hex.toIntOrNull(16)
                                    ?: throw JsonException("非法 \\u 转义: $hex")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> throw JsonException("非法转义 \\$e（偏移 $i）")
                        }
                        i++
                    }
                    else -> {
                        if (c.code < 0x20) throw JsonException("字符串含未转义控制字符（偏移 $i）")
                        sb.append(c)
                        i++
                    }
                }
            }
        }

        private fun parseNumber(): Any {
            val start = i
            if (i < s.length && s[i] == '-') i++
            if (atEnd() || !s[i].isDigit()) throw JsonException("非法数字（偏移 $start）")
            while (i < s.length && s[i].isDigit()) i++
            var isDouble = false
            if (i < s.length && s[i] == '.') {
                isDouble = true
                i++
                if (atEnd() || !s[i].isDigit()) throw JsonException("非法小数（偏移 $i）")
                while (i < s.length && s[i].isDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isDouble = true
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (atEnd() || !s[i].isDigit()) throw JsonException("非法指数（偏移 $i）")
                while (i < s.length && s[i].isDigit()) i++
            }
            val token = s.substring(start, i)
            if (!isDouble) {
                token.toLongOrNull()?.let { return it }
            }
            return token.toDouble()
        }
    }

    // ---------------------------- 取值辅助 ----------------------------

    @Suppress("UNCHECKED_CAST")
    fun obj(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun arr(v: Any?): List<Any?>? = v as? List<Any?>

    fun str(v: Any?): String? = v as? String

    fun bool(v: Any?): Boolean? = v as? Boolean

    fun long(v: Any?): Long? = when (v) {
        null -> null
        is Long -> v
        is Int -> v.toLong()
        is Short -> v.toLong()
        is Byte -> v.toLong()
        is Double -> if (v.isFinite() && v == kotlin.math.floor(v)) v.toLong() else null
        is Float -> long(v.toDouble())
        is String -> v.toLongOrNull()
        else -> null
    }

    fun int(v: Any?): Int? {
        val l = long(v) ?: return null
        return if (l in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) l.toInt() else null
    }

    fun double(v: Any?): Double? = when (v) {
        is Double -> v
        is Float -> v.toDouble()
        is Long -> v.toDouble()
        is Int -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }

    // ---------------------------- 序列化 ----------------------------

    fun write(v: Any?): String {
        val sb = StringBuilder()
        writeValue(sb, v)
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (v) "true" else "false")
            is String -> writeString(sb, v)
            is Double -> if (v.isNaN() || v.isInfinite()) sb.append("null") else sb.append(v.toString())
            is Float -> writeValue(sb, v.toDouble())
            is Number -> sb.append(v.toString()) // Long / Int / Short / Byte
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    writeValue(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeValue(sb, item)
                }
                sb.append(']')
            }
            is Array<*> -> writeValue(sb, v.toList())
            is ByteArray -> writeString(sb, base64Encode(v))
            is IntArray -> writeValue(sb, v.toList())
            is LongArray -> writeValue(sb, v.toList())
            is ShortArray -> writeValue(sb, v.toList())
            is FloatArray -> writeValue(sb, v.toList())
            is DoubleArray -> writeValue(sb, v.toList())
            is BooleanArray -> writeValue(sb, v.toList())
            is CharArray -> writeValue(sb, v.toList())
            is Char -> writeString(sb, v.toString())
            else -> writeString(sb, v.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c.code < 0x20 -> sb.append("\\u").append(String.format(Locale.ROOT, "%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    // ---------------------------- Base64（纯 Kotlin，NO_WRAP 风格） ----------------------------

    private const val B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun base64Encode(data: ByteArray): String {
        val sb = StringBuilder(((data.size + 2) / 3) * 4)
        var i = 0
        while (i + 3 <= data.size) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or
                ((data[i + 1].toInt() and 0xFF) shl 8) or
                (data[i + 2].toInt() and 0xFF)
            sb.append(B64_ALPHABET[(n ushr 18) and 63])
            sb.append(B64_ALPHABET[(n ushr 12) and 63])
            sb.append(B64_ALPHABET[(n ushr 6) and 63])
            sb.append(B64_ALPHABET[n and 63])
            i += 3
        }
        val remain = data.size - i
        if (remain == 1) {
            val n = (data[i].toInt() and 0xFF) shl 16
            sb.append(B64_ALPHABET[(n ushr 18) and 63])
            sb.append(B64_ALPHABET[(n ushr 12) and 63])
            sb.append("==")
        } else if (remain == 2) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8)
            sb.append(B64_ALPHABET[(n ushr 18) and 63])
            sb.append(B64_ALPHABET[(n ushr 12) and 63])
            sb.append(B64_ALPHABET[(n ushr 6) and 63])
            sb.append('=')
        }
        return sb.toString()
    }

    fun base64Decode(text: String): ByteArray {
        val compact = StringBuilder(text.length)
        for (c in text) {
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') continue
            compact.append(c)
        }
        var padding = 0
        var length = compact.length
        while (length > 0 && compact[length - 1] == '=') {
            padding++
            length--
        }
        if (padding > 2) throw JsonException("非法 Base64 填充")
        val out = ArrayList<Byte>((length * 3) / 4 + 3)
        var buffer = 0
        var bits = 0
        for (idx in 0 until length) {
            val v = B64_ALPHABET.indexOf(compact[idx])
            if (v < 0) throw JsonException("非法 Base64 字符: ${compact[idx]}")
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer ushr bits) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }

    // ---------------------------- JSON-RPC 响应构建辅助 ----------------------------

    /** 成功响应：{"jsonrpc":"2.0","id":<id>,"result":<result>} */
    fun rpcResult(id: Any?, result: Any?): String =
        write(linkedMapOf("jsonrpc" to "2.0", "id" to id, "result" to result))

    /** 错误响应：{"jsonrpc":"2.0","id":<id>,"error":{"code":..,"message":..[, "data":..]}} */
    fun rpcError(id: Any?, code: Int, message: String, data: Any? = null): String {
        val error = LinkedHashMap<String, Any?>()
        error["code"] = code
        error["message"] = message
        if (data != null) error["data"] = data
        return write(linkedMapOf("jsonrpc" to "2.0", "id" to id, "error" to error))
    }
}

// ============================ 工具条目 JSON 化 ============================

private fun McpAssetEntry.toJson(): Map<String, Any?> = linkedMapOf(
    "pathId" to pathId,
    "name" to name,
    "type" to type,
    "byteSize" to byteSize,
    "fileName" to fileName,
    "containerPath" to containerPath
)

private fun McpFileSummary.toJson(): Map<String, Any?> = linkedMapOf(
    "fileName" to fileName,
    "unityVersion" to unityVersion,
    "objectCount" to objectCount
)

// ============================ MCP 服务器核心 ============================

/**
 * JSON-RPC 2.0 / MCP 消息分发器。无状态（除构造参数），可被 HTTP/stdio 等任意传输层复用。
 */
class McpServerCore(
    private val serverName: String,
    private val serverVersion: String,
    private val sourceProvider: () -> McpAssetSource?
) {
    companion object {
        /** export_asset 支持的格式 */
        val EXPORT_FORMATS: List<String> = listOf("png", "txt", "obj", "raw")

        private const val PAGE_SCAN_SIZE = 200
        private const val MAX_SCAN_ROUNDS = 200_000
    }

    // ---------------------------- 工具定义 ----------------------------

    private class ToolSpec(
        val name: String,
        val description: String,
        val properties: LinkedHashMap<String, Map<String, Any?>>,
        val required: List<String>
    ) {
        fun toJson(): Map<String, Any?> {
            val schema = LinkedHashMap<String, Any?>()
            schema["type"] = "object"
            schema["properties"] = properties
            if (required.isNotEmpty()) schema["required"] = required.toList()
            return linkedMapOf(
                "name" to name,
                "description" to description,
                "inputSchema" to schema
            )
        }
    }

    private fun stringProp(description: String): Map<String, Any?> =
        linkedMapOf("type" to "string", "description" to description)

    private fun intProp(description: String, default: Int, min: Int, max: Int): Map<String, Any?> =
        linkedMapOf(
            "type" to "integer",
            "description" to description,
            "default" to default,
            "minimum" to min,
            "maximum" to max
        )

    private fun enumProp(description: String, values: List<String>, default: String): Map<String, Any?> =
        linkedMapOf(
            "type" to "string",
            "description" to description,
            "enum" to values,
            "default" to default
        )

    private val tools: List<ToolSpec> = listOf(
        ToolSpec(
            "get_overview",
            "获取已加载资源文件总览：文件列表、各类型数量分布与总大小，无需参数",
            LinkedHashMap(),
            emptyList()
        ),
        ToolSpec(
            "list_assets",
            "分页列出已加载的资源对象，可按类型（对象类名，如 Texture2D/Sprite/TextAsset/Mesh/MonoBehaviour/" +
                "SkinnedMeshRenderer/MeshFilter）与关键字（名称或容器路径，不区分大小写）过滤",
            linkedMapOf(
                "type" to stringProp("按对象类名过滤，如 Texture2D、Sprite、TextAsset、Mesh、SkinnedMeshRenderer、MeshFilter、MonoBehaviour；不传返回全部类型"),
                "keyword" to stringProp("关键字过滤：匹配资源名称或容器路径（不区分大小写）"),
                "offset" to intProp("分页起始偏移", 0, 0, 1_000_000),
                "limit" to intProp("每页数量", 50, 1, 200)
            ),
            emptyList()
        ),
        ToolSpec(
            "search_assets",
            "按关键字搜索资源（匹配名称或容器路径，不区分大小写），返回前 limit 条",
            linkedMapOf(
                "keyword" to stringProp("搜索关键字（必填）：匹配资源名称或容器路径，不区分大小写"),
                "limit" to intProp("最多返回条数", 20, 1, 200)
            ),
            listOf("keyword")
        ),
        ToolSpec(
            "get_asset_detail",
            "获取单个资源的详细信息：类型、名称、pathID、字节大小、所在文件、容器路径及类型专属字段" +
                "（贴图宽高/格式/mip/流式信息、网格顶点/子网格/三角形数、SkinnedMeshRenderer 骨骼/材质槽/" +
                "混合形状权重与引用网格统计、MeshFilter 网格引用、文本摘要、音频采样率/声道/时长等）",
            linkedMapOf(
                "pathId" to pathIdProp(),
                "fileName" to stringProp("所在序列化文件名（可选，用于区分不同文件中相同的 PathID）")
            ),
            listOf("pathId")
        ),
        ToolSpec(
            "read_text_asset",
            "读取 TextAsset 的文本内容（按 maxChars 截断）",
            linkedMapOf(
                "pathId" to pathIdProp(),
                "maxChars" to intProp("最多返回的字符数", 20_000, 100, 200_000)
            ),
            listOf("pathId")
        ),
        ToolSpec(
            "get_texture_preview",
            "将 Texture2D / Sprite 解码并按最长边缩放为 PNG 预览图，以 base64 返回",
            linkedMapOf(
                "pathId" to pathIdProp(),
                "maxSize" to intProp("预览图最长边像素", 512, 64, 1024)
            ),
            listOf("pathId")
        ),
        ToolSpec(
            "get_texture_stats",
            "统计所有 Texture2D：总数、格式分布（数量/磁盘字节/显存字节估算）、分辨率分布、" +
                "内联与流式数量、按磁盘字节排序的 Top10，无需参数",
            LinkedHashMap(),
            emptyList()
        ),
        ToolSpec(
            "dump_render_asset",
            "将渲染相关资产转储为可读文本：Material（纹理槽/属性表）、Shader（子着色器/Pass 结构）、" +
                "SkinnedMeshRenderer（网格引用/骨骼列表/材质槽/混合形状权重）、MeshFilter（网格引用）",
            linkedMapOf(
                "pathId" to pathIdProp(),
                "fileName" to stringProp("所在序列化文件名（可选，用于区分不同文件中相同的 PathID）")
            ),
            listOf("pathId")
        ),
        ToolSpec(
            "export_asset",
            "将资源导出到导出目录：png（Texture2D/Sprite）、txt（TextAsset 全文或 Material/Shader/" +
                "SkinnedMeshRenderer/MeshFilter 转储）、obj（Mesh 或 SkinnedMeshRenderer/MeshFilter 引用的网格）、" +
                "raw（原始序列化字节，扩展名 .dat），返回导出文件绝对路径",
            linkedMapOf(
                "pathId" to pathIdProp(),
                "fileName" to stringProp("所在序列化文件名（可选，用于区分不同文件中相同的 PathID）"),
                "format" to enumProp("导出格式", EXPORT_FORMATS, "png")
            ),
            listOf("pathId")
        )
    )

    private fun pathIdProp(): Map<String, Any?> =
        linkedMapOf(
            "type" to "integer",
            "description" to "资源 PathID（必填，可通过 list_assets / search_assets 获取）"
        )

    // ---------------------------- 入口 ----------------------------

    fun handle(raw: String): McpOutcome {
        return try {
            handleInternal(raw)
        } catch (e: Exception) {
            McpOutcome.Reply(
                Json.rpcError(null, McpProtocol.INTERNAL_ERROR, "内部错误: ${e.message ?: e.javaClass.simpleName}")
            )
        }
    }

    private fun handleInternal(raw: String): McpOutcome {
        val root = try {
            Json.parse(raw)
        } catch (e: Exception) {
            return McpOutcome.Reply(
                Json.rpcError(null, McpProtocol.PARSE_ERROR, "JSON 解析失败: ${e.message ?: "非法 JSON"}")
            )
        }
        val msg = Json.obj(root)
            ?: return McpOutcome.Reply(
                Json.rpcError(null, McpProtocol.INVALID_REQUEST, "请求必须是 JSON 对象（JSON-RPC 2.0）")
            )

        val method = Json.str(msg["method"])
        if (method == null) {
            // 客户端发来的 response 消息（有 result / error 而无 method）：无需应答
            if (msg.containsKey("result") || msg.containsKey("error")) {
                return McpOutcome.Accepted
            }
            return McpOutcome.Reply(
                Json.rpcError(null, McpProtocol.INVALID_REQUEST, "请求缺少 method 字段")
            )
        }

        // 无 id 视为通知（notifications 前缀等）：一律不应答
        val id = msg["id"]
        if (id == null) return McpOutcome.Accepted

        // 带了 id 的 notifications 前缀消息同样不应答（容忍不规范的客户端）
        if (method.startsWith("notifications/")) return McpOutcome.Accepted

        val params = Json.obj(msg["params"]) ?: LinkedHashMap()

        val result: Any? = when (method) {
            "initialize" -> handleInitialize(params)
            "ping" -> LinkedHashMap<String, Any?>()
            "tools/list" -> linkedMapOf("tools" to tools.map { it.toJson() })
            "tools/call" -> handleToolsCall(params)
            else -> return McpOutcome.Reply(
                Json.rpcError(id, McpProtocol.METHOD_NOT_FOUND, "未知方法: $method")
            )
        }
        return McpOutcome.Reply(Json.rpcResult(id, result))
    }

    // ---------------------------- 方法实现 ----------------------------

    private fun handleInitialize(params: Map<String, Any?>): Map<String, Any?> {
        val requested = Json.str(params["protocolVersion"])
        val negotiated =
            if (requested != null && requested in McpProtocol.SUPPORTED) requested else McpProtocol.VERSION
        return linkedMapOf(
            "protocolVersion" to negotiated,
            "capabilities" to linkedMapOf<String, Any?>(
                "tools" to linkedMapOf<String, Any?>()
            ),
            "serverInfo" to linkedMapOf(
                "name" to serverName,
                "version" to serverVersion,
                "author" to "醉莫"
            )
        )
    }

    private fun handleToolsCall(params: Map<String, Any?>): Map<String, Any?> {
        val name = Json.str(params["name"])
            ?: return toolError("缺少工具名（params.name）")
        if (tools.none { it.name == name }) {
            return toolError("未知工具: $name")
        }
        val args = Json.obj(params["arguments"]) ?: LinkedHashMap()
        return try {
            when (name) {
                "get_overview" -> callGetOverview()
                "list_assets" -> callListAssets(args)
                "search_assets" -> callSearchAssets(args)
                "get_asset_detail" -> callGetAssetDetail(args)
                "read_text_asset" -> callReadTextAsset(args)
                "get_texture_preview" -> callGetTexturePreview(args)
                "get_texture_stats" -> callGetTextureStats()
                "dump_render_asset" -> callDumpRenderAsset(args)
                "export_asset" -> callExportAsset(args)
                else -> toolError("未知工具: $name")
            }
        } catch (e: Exception) {
            toolError("工具 $name 执行失败: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun source(): McpAssetSource? = try {
        sourceProvider()
    } catch (e: Exception) {
        null
    }

    // ---------------------------- 工具实现 ----------------------------

    private fun callGetOverview(): Map<String, Any?> {
        val src = source() ?: return toolError("尚未加载任何资源文件")
        val files = src.loadedFiles()

        // 分页扫描全部对象（limit 上限 200，循环取完为止）
        val entries = ArrayList<McpAssetEntry>()
        var offset = 0
        var rounds = 0
        while (rounds++ < MAX_SCAN_ROUNDS) {
            val page = src.listAssets(null, null, offset, PAGE_SCAN_SIZE)
            if (page.items.isEmpty()) break
            entries.addAll(page.items)
            offset += page.items.size
            if (page.total > 0 && entries.size >= page.total) break
        }

        val distribution = LinkedHashMap<String, Int>()
        for (e in entries) distribution[e.type] = (distribution[e.type] ?: 0) + 1
        val sortedTypes = distribution.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        val typeDistribution = LinkedHashMap<String, Any?>()
        for ((k, v) in sortedTypes) typeDistribution[k] = v

        val totalBytes = entries.fold(0L) { acc, e -> acc + e.byteSize }

        val structured = linkedMapOf(
            "fileCount" to files.size,
            "assetCount" to entries.size,
            "typeDistribution" to typeDistribution,
            "totalBytes" to totalBytes,
            "files" to files.map { it.toJson() }
        )

        val text = buildString {
            append("已加载 ${files.size} 个文件，共 ${entries.size} 个资源，合计 ${fmtBytes(totalBytes)}\n")
            for (f in files) {
                append("文件: ${f.fileName}（Unity ${f.unityVersion}，${f.objectCount} 个对象）\n")
            }
            append("类型分布: ")
            append(
                if (sortedTypes.isEmpty()) "（无）"
                else sortedTypes.joinToString(", ") { "${it.key} x${it.value}" }
            )
        }
        return toolResult(listOf(textContent(text)), structured)
    }

    private fun callListAssets(args: Map<String, Any?>): Map<String, Any?> {
        val src = source() ?: return toolError("尚未加载任何资源文件")
        val type = optionalString(args, "type")
        val keyword = optionalString(args, "keyword")
        val offset = intArg(args, "offset", 0, 0, 1_000_000)
        val limit = intArg(args, "limit", 50, 1, 200)

        val page = src.listAssets(type, keyword, offset, limit)
        val structured = linkedMapOf(
            "items" to page.items.map { it.toJson() },
            "total" to page.total,
            "offset" to page.offset,
            "limit" to page.limit
        )
        val text = buildString {
            append(
                "共 ${page.total} 个资源（offset=${page.offset}, limit=${page.limit}），本页 ${page.items.size} 条"
            )
            if (type != null) append("，类型过滤: $type")
            if (keyword != null) append("，关键字: $keyword")
            append('\n')
            for (e in page.items) {
                append("[${e.type}] ${e.name} (pathId=${e.pathId}, ${fmtBytes(e.byteSize)}, ${e.fileName}")
                if (e.containerPath.isNotEmpty()) append(", ${e.containerPath}")
                append(")\n")
            }
        }
        return toolResult(listOf(textContent(text.trimEnd('\n'))), structured)
    }

    private fun callSearchAssets(args: Map<String, Any?>): Map<String, Any?> {
        val src = source() ?: return toolError("尚未加载任何资源文件")
        val keyword = Json.str(args["keyword"])
        if (keyword.isNullOrEmpty()) return toolError("缺少必填参数 keyword")
        val limit = intArg(args, "limit", 20, 1, 200)

        val results = src.searchAssets(keyword, limit)
        val structured = linkedMapOf(
            "keyword" to keyword,
            "count" to results.size,
            "results" to results.map { it.toJson() }
        )
        val text = buildString {
            append("关键字「$keyword」命中 ${results.size} 条（最多返回 $limit 条）\n")
            for (e in results) {
                append("[${e.type}] ${e.name} (pathId=${e.pathId}, ${fmtBytes(e.byteSize)}, ${e.fileName}")
                if (e.containerPath.isNotEmpty()) append(", ${e.containerPath}")
                append(")\n")
            }
        }
        return toolResult(listOf(textContent(text.trimEnd('\n'))), structured)
    }

    private fun callGetAssetDetail(args: Map<String, Any?>): Map<String, Any?> {
        val src = source() ?: return toolError("尚未加载任何资源文件")
        val pathId = Json.long(args["pathId"]) ?: return toolError("缺少必填参数 pathId")
        val fileName = optionalString(args, "fileName")

        val detail = src.assetDetail(pathId, fileName)
            ?: return toolError(notFoundMessage(pathId, fileName))
        return toolResult(listOf(textContent(renderMapText(detail))), LinkedHashMap(detail))
    }

    private fun callReadTextAsset(args: Map<String, Any?>): Map<String, Any?> {
        val src = source() ?: return toolError("尚未加载任何资源文件")
        val pathId = Json.long(args["pathId"]) ?: return toolError("缺少必填参数 pathId")
        val maxChars = intArg(args, "maxChars", 20_000, 100, 200_000)

        val full = src.textContent(pathId, null)
            ?: return toolError("pathId=$pathId 不是 TextAsset（或不存在）")
        val truncated = full.length > maxChars
        val content = if (truncated) full.substring(0, maxChars) else full
        val structured = linkedMapOf(
            "pathId" to pathId,
            "length" to full.length,
            "maxChars" to maxChars,
            "truncated" to truncated,
            "content" to content
        )
        val note = if (truncated) "\n（共 ${full.length} 字符，已截断至 $maxChars）" else ""
        return toolResult(listOf(textContent(content + note)), structured)
    }

    private fun callGetTexturePreview(args: Map<String, Any?>): Map<String, Any?> {
        val src = source() ?: return toolError("尚未加载任何资源文件")
        val pathId = Json.long(args["pathId"]) ?: return toolError("缺少必填参数 pathId")
        val maxSize = intArg(args, "maxSize", 512, 64, 1024)

        val png = src.texturePreviewPng(pathId, maxSize)
            ?: return toolError("pathId=$pathId 不是 Texture2D / Sprite（或解码失败）")
        val content = listOf(
            linkedMapOf<String, Any?>(
                "type" to "image",
                "data" to Json.base64Encode(png),
                "mimeType" to "image/png"
            ),
            textContent("已生成 PNG 预览：${png.size} 字节（maxSize=$maxSize）")
        )
        val structured = linkedMapOf(
            "pathId" to pathId,
            "maxSize" to maxSize,
            "sizeBytes" to png.size
        )
        return toolResult(content, structured)
    }

    private fun callGetTextureStats(): Map<String, Any?> {
        val src = source() ?: return toolError("尚未加载任何资源文件")
        val stats = src.textureStats()
        return toolResult(listOf(textContent(renderMapText(stats))), LinkedHashMap(stats))
    }

    private fun callDumpRenderAsset(args: Map<String, Any?>): Map<String, Any?> {
        val src = source() ?: return toolError("尚未加载任何资源文件")
        val pathId = Json.long(args["pathId"]) ?: return toolError("缺少必填参数 pathId")
        val fileName = optionalString(args, "fileName") // 空串按 null 处理

        val dump = src.renderDump(pathId, fileName)
            ?: return toolError("pathId=$pathId 不是 Material / Shader（或不存在）")
        val structured = linkedMapOf(
            "pathId" to pathId,
            "fileName" to (fileName ?: ""),
            "length" to dump.length,
            "content" to dump
        )
        return toolResult(listOf(textContent(dump)), structured)
    }

    private fun callExportAsset(args: Map<String, Any?>): Map<String, Any?> {
        val src = source() ?: return toolError("尚未加载任何资源文件")
        val pathId = Json.long(args["pathId"]) ?: return toolError("缺少必填参数 pathId")
        val fileName = optionalString(args, "fileName")
        val format = (Json.str(args["format"]) ?: "png").lowercase(Locale.ROOT)
        if (format !in EXPORT_FORMATS) {
            return toolError("不支持的导出格式「$format」，可选：${EXPORT_FORMATS.joinToString("/")}")
        }

        val path = src.exportAsset(pathId, fileName, format)
        val baseName = path.substringAfterLast('/').substringAfterLast('\\')
        val structured = linkedMapOf(
            "pathId" to pathId,
            "format" to format,
            "path" to path,
            "fileName" to baseName
        )
        return toolResult(listOf(textContent("已导出 $format：$path")), structured)
    }

    // ---------------------------- 结果构建 ----------------------------

    private fun toolResult(
        content: List<Map<String, Any?>>,
        structuredContent: Map<String, Any?>
    ): Map<String, Any?> = linkedMapOf(
        "content" to content,
        "structuredContent" to structuredContent,
        "isError" to false
    )

    private fun toolError(message: String): Map<String, Any?> = linkedMapOf(
        "content" to listOf(textContent(message)),
        "structuredContent" to linkedMapOf<String, Any?>("error" to message),
        "isError" to true
    )

    private fun textContent(text: String): Map<String, Any?> =
        linkedMapOf("type" to "text", "text" to text)

    private fun notFoundMessage(pathId: Long, fileName: String?): String =
        if (fileName != null) "pathId=$pathId 不存在（fileName=$fileName）" else "pathId=$pathId 不存在"

    // ---------------------------- 参数辅助 ----------------------------

    private fun optionalString(args: Map<String, Any?>, key: String): String? {
        val v = Json.str(args[key]) ?: return null
        return v.ifEmpty { null }
    }

    private fun intArg(args: Map<String, Any?>, key: String, default: Int, min: Int, max: Int): Int {
        val v = Json.long(args[key]) ?: return default
        return v.coerceIn(min.toLong(), max.toLong()).toInt()
    }

    // ---------------------------- 文本渲染 ----------------------------

    private fun fmtBytes(v: Long): String = when {
        v >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB", v / 1048576.0)
        v >= 1L shl 10 -> String.format(Locale.ROOT, "%.1f KB", v / 1024.0)
        else -> "$v B"
    }

    /** 把结构化结果渲染成可读文本（供 content[0].text 使用） */
    private fun renderMapText(map: Map<String, Any?>): String {
        val sb = StringBuilder()
        for ((k, v) in map) {
            when (v) {
                is Map<*, *> -> {
                    sb.append(k).append(":\n")
                    for ((k2, v2) in v) {
                        sb.append("  ").append(k2).append(": ").append(renderInline(v2)).append('\n')
                    }
                }
                is List<*> -> {
                    sb.append(k).append(" (").append(v.size).append(" 项):\n")
                    for (item in v) {
                        sb.append("  - ").append(renderInline(item)).append('\n')
                    }
                }
                else -> sb.append(k).append(": ").append(renderInline(v)).append('\n')
            }
        }
        return sb.toString().trimEnd('\n')
    }

    private fun renderInline(v: Any?): String = when (v) {
        null -> "null"
        is Map<*, *> -> v.entries.joinToString(", ") { "${it.key}=${renderInline(it.value)}" }
        is List<*> -> v.joinToString(", ") { renderInline(it) }
        else -> v.toString()
    }
}

package com.assetstudio.mobile.core.serialized

/*
 * 来源: AssetStudio/TypeTreeHelper.cs
 *
 * 按 TypeTree 节点定义从二进制数据中读取对象内容。
 * - readType       : 返回 LinkedHashMap<String, Any?> / List<Any?> / Pair 列表 / 基础类型，
 *                    对应 C# 的 OrderedDictionary / List / KeyValuePair / object。
 * - readTypeString : 返回与 C# ReadTypeString 相同格式的文本转储（\t 缩进 + \r\n 换行）。
 *
 * 类型映射（与 C# 一致的无符号语义）：
 * - SInt8 -> Byte, UInt8 -> Int(0..255), char -> Char(2字节，BitConverter 小端语义)
 * - SInt16 -> Short, UInt16 -> Int(0..65535)
 * - SInt32 -> Int,  UInt32 -> Long(0..4294967295)
 * - SInt64 -> Long, UInt64 -> Long(按位原值，字符串输出用无符号十进制)
 * - float -> Float, double -> Double, bool -> Boolean
 * - string -> String, TypelessData -> ByteArray, map -> List<Pair<Any?, Any?>>
 * - 数组 -> List<Any?>, 嵌套类 -> LinkedHashMap<String, Any?>
 */

import com.assetstudio.mobile.core.ObjectReader
import com.assetstudio.mobile.core.io.EndianBinaryReader

object TypeTreeHelper {

    /** C# 的 ref int 参数在 Kotlin 中的等价物 */
    private class IndexRef(var value: Int)

    // ============================ 对外入口 ============================

    /** 对应 C# ReadTypeString(TypeTree, ObjectReader) */
    fun readTypeString(m_Type: TypeTree, reader: EndianBinaryReader): String {
        if (reader is ObjectReader) {
            reader.reset()
        }
        val sb = StringBuilder()
        val m_Nodes = m_Type.m_Nodes
        val iRef = IndexRef(0)
        while (iRef.value < m_Nodes.size) {
            readStringValue(sb, m_Nodes, reader, iRef)
            iRef.value++
        }
        checkReadSize(reader)
        return sb.toString()
    }

    /** 对应 C# ReadType(TypeTree, ObjectReader)，入口从根节点的子节点开始 */
    fun readType(m_Types: TypeTree, reader: EndianBinaryReader): Any {
        return readType(m_Types.m_Nodes, reader)
    }

    /** 直接以节点列表读取（根节点为 nodes[0]，实际数据从 nodes[1] 开始） */
    fun readType(nodes: List<TypeTreeNode>, reader: EndianBinaryReader): Any {
        if (reader is ObjectReader) {
            reader.reset()
        }
        val obj = LinkedHashMap<String, Any?>()
        val iRef = IndexRef(1)
        while (iRef.value < nodes.size) {
            val m_Node = nodes[iRef.value]
            val varNameStr = m_Node.m_Name
            obj[varNameStr] = readValue(nodes, reader, iRef)
            iRef.value++
        }
        checkReadSize(reader)
        return obj
    }

    // ============================ 字符串转储 ============================

    private fun readStringValue(
        sb: StringBuilder,
        m_Nodes: List<TypeTreeNode>,
        reader: EndianBinaryReader,
        iRef: IndexRef
    ) {
        val i = iRef.value
        val m_Node = m_Nodes[i]
        val level = m_Node.m_Level
        val varTypeStr = m_Node.m_Type
        val varNameStr = m_Node.m_Name
        var value: Any? = null
        var unsigned64 = false
        var append = true
        var align = (m_Node.m_MetaFlag and 0x4000) != 0
        when (varTypeStr) {
            "SInt8" -> value = reader.readInt8()
            "UInt8" -> value = reader.readUInt8()
            "char" -> value = readChar(reader)
            "short", "SInt16" -> value = reader.readInt16()
            "UInt16", "unsigned short" -> value = reader.readUInt16()
            "int", "SInt32" -> value = reader.readInt32()
            "UInt32", "unsigned int", "Type*" -> value = reader.readUInt32()
            "long long", "SInt64" -> value = reader.readInt64()
            "UInt64", "unsigned long long", "FileSize" -> {
                value = reader.readUInt64()
                unsigned64 = true
            }
            "float" -> value = reader.readSingle()
            "double" -> value = reader.readDouble()
            "bool" -> value = reader.readBoolean()
            "string" -> {
                append = false
                val str = reader.readAlignedString()
                sb.append(tabs(level)).append(varTypeStr).append(' ').append(varNameStr)
                    .append(" = \"").append(str).append("\"\r\n")
                val toSkip = getNodes(m_Nodes, i)
                iRef.value += toSkip.size - 1
            }
            "map" -> {
                if (i + 1 < m_Nodes.size && (m_Nodes[i + 1].m_MetaFlag and 0x4000) != 0) {
                    align = true
                }
                append = false
                sb.append(tabs(level)).append(varTypeStr).append(' ').append(varNameStr).append("\r\n")
                sb.append(tabs(level + 1)).append("Array Array\r\n")
                val size = reader.readInt32()
                sb.append(tabs(level + 1)).append("int size = ").append(size).append("\r\n")
                val map = getNodes(m_Nodes, i)
                iRef.value += map.size - 1
                val first = getNodes(map, 4)
                val next = 4 + first.size
                val second = getNodes(map, next)
                for (j in 0 until size) {
                    sb.append(tabs(level + 2)).append('[').append(j).append("]\r\n")
                    sb.append(tabs(level + 2)).append("pair data\r\n")
                    val tmp1 = IndexRef(0)
                    val tmp2 = IndexRef(0)
                    readStringValue(sb, first, reader, tmp1)
                    readStringValue(sb, second, reader, tmp2)
                }
            }
            "TypelessData" -> {
                append = false
                val size = reader.readInt32()
                reader.readBytes(size)
                iRef.value += 2
                sb.append(tabs(level)).append(varTypeStr).append(' ').append(varNameStr).append("\r\n")
                sb.append(tabs(level)).append("int size = ").append(size).append("\r\n")
            }
            else -> {
                if (i < m_Nodes.size - 1 && m_Nodes[i + 1].m_Type == "Array") { // Array
                    if ((m_Nodes[i + 1].m_MetaFlag and 0x4000) != 0) {
                        align = true
                    }
                    append = false
                    sb.append(tabs(level)).append(varTypeStr).append(' ').append(varNameStr).append("\r\n")
                    sb.append(tabs(level + 1)).append("Array Array\r\n")
                    val size = reader.readInt32()
                    sb.append(tabs(level + 1)).append("int size = ").append(size).append("\r\n")
                    val vector = getNodes(m_Nodes, i)
                    iRef.value += vector.size - 1
                    for (j in 0 until size) {
                        sb.append(tabs(level + 2)).append('[').append(j).append("]\r\n")
                        val tmp = IndexRef(3)
                        readStringValue(sb, vector, reader, tmp)
                    }
                } else { // Class
                    append = false
                    sb.append(tabs(level)).append(varTypeStr).append(' ').append(varNameStr).append("\r\n")
                    val klass = getNodes(m_Nodes, i)
                    iRef.value += klass.size - 1
                    val jRef = IndexRef(1)
                    while (jRef.value < klass.size) {
                        readStringValue(sb, klass, reader, jRef)
                        jRef.value++
                    }
                }
            }
        }
        if (append) {
            sb.append(tabs(level)).append(varTypeStr).append(' ').append(varNameStr)
                .append(" = ").append(formatValue(value, unsigned64)).append("\r\n")
        }
        if (align) {
            reader.alignStream()
        }
    }

    // ============================ 结构化读取 ============================

    private fun readValue(m_Nodes: List<TypeTreeNode>, reader: EndianBinaryReader, iRef: IndexRef): Any? {
        val i = iRef.value
        val m_Node = m_Nodes[i]
        val varTypeStr = m_Node.m_Type
        val value: Any?
        var align = (m_Node.m_MetaFlag and 0x4000) != 0
        when (varTypeStr) {
            "SInt8" -> value = reader.readInt8()
            "UInt8" -> value = reader.readUInt8()
            "char" -> value = readChar(reader)
            "short", "SInt16" -> value = reader.readInt16()
            "UInt16", "unsigned short" -> value = reader.readUInt16()
            "int", "SInt32" -> value = reader.readInt32()
            "UInt32", "unsigned int", "Type*" -> value = reader.readUInt32()
            "long long", "SInt64" -> value = reader.readInt64()
            "UInt64", "unsigned long long", "FileSize" -> value = reader.readUInt64()
            "float" -> value = reader.readSingle()
            "double" -> value = reader.readDouble()
            "bool" -> value = reader.readBoolean()
            "string" -> {
                value = reader.readAlignedString()
                val toSkip = getNodes(m_Nodes, i)
                iRef.value += toSkip.size - 1
            }
            "map" -> {
                if (i + 1 < m_Nodes.size && (m_Nodes[i + 1].m_MetaFlag and 0x4000) != 0) {
                    align = true
                }
                val map = getNodes(m_Nodes, i)
                iRef.value += map.size - 1
                val first = getNodes(map, 4)
                val next = 4 + first.size
                val second = getNodes(map, next)
                val size = reader.readInt32()
                val dic = ArrayList<Pair<Any?, Any?>>(size.coerceAtLeast(0))
                for (j in 0 until size) {
                    val tmp1 = IndexRef(0)
                    val tmp2 = IndexRef(0)
                    dic.add(Pair(readValue(first, reader, tmp1), readValue(second, reader, tmp2)))
                }
                value = dic
            }
            "TypelessData" -> {
                val size = reader.readInt32()
                value = reader.readBytes(size)
                iRef.value += 2
            }
            else -> {
                if (i < m_Nodes.size - 1 && m_Nodes[i + 1].m_Type == "Array") { // Array
                    if ((m_Nodes[i + 1].m_MetaFlag and 0x4000) != 0) {
                        align = true
                    }
                    val vector = getNodes(m_Nodes, i)
                    iRef.value += vector.size - 1
                    val size = reader.readInt32()
                    val list = ArrayList<Any?>(size.coerceAtLeast(0))
                    for (j in 0 until size) {
                        val tmp = IndexRef(3)
                        list.add(readValue(vector, reader, tmp))
                    }
                    value = list
                } else { // Class
                    val klass = getNodes(m_Nodes, i)
                    iRef.value += klass.size - 1
                    val obj = LinkedHashMap<String, Any?>()
                    val jRef = IndexRef(1)
                    while (jRef.value < klass.size) {
                        val classmember = klass[jRef.value]
                        val name = classmember.m_Name
                        obj[name] = readValue(klass, reader, jRef)
                        jRef.value++
                    }
                    value = obj
                }
            }
        }
        if (align) {
            reader.alignStream()
        }
        return value
    }

    // ============================ 辅助 ============================

    /** 对应 C# GetNodes：取 index 节点及其全部后代（层级大于其 level 的连续节点） */
    private fun getNodes(m_Nodes: List<TypeTreeNode>, index: Int): List<TypeTreeNode> {
        val nodes = ArrayList<TypeTreeNode>()
        nodes.add(m_Nodes[index])
        val level = m_Nodes[index].m_Level
        for (i in index + 1 until m_Nodes.size) {
            val member = m_Nodes[i]
            if (member.m_Level <= level) {
                return nodes
            }
            nodes.add(member)
        }
        return nodes
    }

    /**
     * C#: BitConverter.ToChar(reader.ReadBytes(2), 0) —— 始终按小端解释，
     * 与 reader 自身大小端设置无关，忠实保留该语义。
     */
    private fun readChar(reader: EndianBinaryReader): Char {
        val b = reader.readBytes(2)
        return ((b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)).toChar()
    }

    /** 对应 C# 读取后 Position - byteStart 与 byteSize 的一致性检查（仅提示，不抛错） */
    private fun checkReadSize(reader: EndianBinaryReader) {
        if (reader is ObjectReader) {
            val readed = reader.position - reader.byteStart
            if (readed != reader.byteSize) {
                // C#: Logger.Info($"Error while read type, read {readed} bytes but expected {reader.byteSize} bytes")
                // 保持与 C# 一致：仅记录提示，不中断
                println("Error while read type, read $readed bytes but expected ${reader.byteSize} bytes")
            }
        }
    }

    private fun tabs(level: Int): String = "\t".repeat(level.coerceAtLeast(0))

    /** 值格式化：尽量与 C# ToString() 输出一致（bool -> True/False，UInt64 -> 无符号） */
    private fun formatValue(value: Any?, unsigned64: Boolean): String {
        return when (value) {
            null -> ""
            is Float -> formatFloat(value)
            is Double -> formatDouble(value)
            is Boolean -> if (value) "True" else "False"
            is Long -> if (unsigned64) java.lang.Long.toUnsignedString(value) else value.toString()
            else -> value.toString()
        }
    }

    /** C# float.ToString()：整数值不带小数点（如 1f -> "1"） */
    private fun formatFloat(value: Float): String {
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
        if (value == value.toLong().toFloat() && kotlin.math.abs(value) < 1e16f) {
            return value.toLong().toString()
        }
        return value.toString()
    }

    /** C# double.ToString()：整数值不带小数点 */
    private fun formatDouble(value: Double): String {
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
        if (value == value.toLong().toDouble() && kotlin.math.abs(value) < 1e16) {
            return value.toLong().toString()
        }
        return value.toString()
    }
}

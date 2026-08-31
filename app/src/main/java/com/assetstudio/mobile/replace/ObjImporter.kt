package com.assetstudio.mobile.replace

/*
 * OBJ 模型导入器：
 * - 支持 v / vn / vt / f / g / o / usemtl 指令
 * - 面按扇形三角化，多 UV/法线组合自动去重合并顶点
 * - 分组（g/o/usemtl）映射为子网格，无分组时整体为单一子网格
 * - 缺少法线时按面法线累加计算平滑法线
 * - 负索引（相对末尾）按 OBJ 规范解析
 *
 * 输出 ObjMeshData：扁平顶点缓冲 + 全局索引表 + 子网格区间。
 */
object ObjImporter {

    class ObjException(message: String) : Exception(message)

    /** 空白切分（解析期间复用，避免每行重复编译正则） */
    private val SPLIT_WS = Regex("\\s+")

    /** 单类顶点/UV/法线索目上限（2^21-1：组合键 Long 打包时 ti+1/ni+1 须严格小于 2^21） */
    private const val MAX_ENTRIES = 2_097_151

    /** 输出顶点（去重后）上限，超出说明文件不适合在手机上替换 */
    private const val MAX_OUT_VERTICES = 3_000_000

    /** 解析结果（全部为最终逐顶点/逐索引数据） */
    class ObjMeshData(
        /** 顶点位置，3/顶点 */
        val positions: FloatArray,
        /** 顶点法线，3/顶点（导入后保证非空，缺省时自动计算） */
        val normals: FloatArray,
        /** UV0，2/顶点；OBJ 无 vt 时为 null */
        val uvs: FloatArray?,
        /** 全局索引表（三角形列表） */
        val indices: IntArray,
        /** 子网格：每组 [起始索引, 数量) */
        val groups: List<Group>,
        val boundsMin: FloatArray,
        val boundsMax: FloatArray
    ) {
        class Group(val name: String, val firstIndex: Int, val indexCount: Int)

        val vertexCount: Int get() = positions.size / 3
        val triangleCount: Int get() = indices.size / 3
    }

    fun parse(text: String): ObjMeshData {
        val posList = ArrayList<FloatArray>()
        val nrmList = ArrayList<FloatArray>()
        val uvList = ArrayList<FloatArray>()

        // 去重表：组合键（vi/ti/ni 打包成 Long）→ 输出顶点索引，
        // 相比字符串键省去每顶点的字符串分配与哈希，大模型解析提速明显
        val vertexMap = HashMap<Long, Int>()
        val outPos = ArrayList<Float>()   // 3/顶点
        val outNrm = ArrayList<Float>()   // 3/顶点
        val outUv = ArrayList<Float>()    // 2/顶点（仅当首个面带 vt 时启用）
        val outIdx = ArrayList<Int>()

        val groups = ArrayList<ObjMeshData.Group>()
        var groupName = "default"
        var groupStart = 0
        var groupFaceCount = 0
        var hasUv = false
        var uvDecided = false

        fun closeGroup(endExclusive: Int) {
            val count = endExclusive - groupStart
            if (count > 0) {
                groups.add(ObjMeshData.Group(groupName, groupStart, count))
            }
            groupStart = endExclusive
            groupFaceCount = 0
        }

        fun toIndex(token: String, size: Int): Int {
            val i = token.toIntOrNull()
                ?: throw ObjException("面索引不是数字: \"$token\"")
            return if (i > 0) i - 1         // 1-based
            else if (i < 0) size + i         // 负数相对末尾
            else throw ObjException("面索引不能为 0")
        }

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(SPLIT_WS)
            when (parts[0]) {
                "v" -> {
                    if (parts.size < 4) throw ObjException("v 行坐标不足: \"$line\"")
                    if (posList.size >= MAX_ENTRIES) {
                        throw ObjException("OBJ 顶点数超过上限 $MAX_ENTRIES，文件过大无法在手机上替换")
                    }
                    posList.add(floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat()))
                }
                "vn" -> {
                    if (parts.size < 4) throw ObjException("vn 行坐标不足: \"$line\"")
                    if (nrmList.size >= MAX_ENTRIES) {
                        throw ObjException("OBJ 法线数超过上限 $MAX_ENTRIES，文件过大无法在手机上替换")
                    }
                    nrmList.add(floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat()))
                }
                "vt" -> {
                    if (parts.size < 3) throw ObjException("vt 行坐标不足: \"$line\"")
                    if (uvList.size >= MAX_ENTRIES) {
                        throw ObjException("OBJ UV 数超过上限 $MAX_ENTRIES，文件过大无法在手机上替换")
                    }
                    // OBJ 与 Unity 的 UV 原点均为左下，直接采用
                    uvList.add(floatArrayOf(parts[1].toFloat(), parts[2].toFloat()))
                }
                "g", "o", "usemtl" -> {
                    // 连续分组声明合并（取最后一个名称）；已有面的组先落盘
                    if (groupFaceCount > 0) closeGroup(outIdx.size)
                    groupName = if (parts.size > 1) parts.drop(1).joinToString(" ") else parts[0]
                    if (groupName.isEmpty()) groupName = parts[0]
                }
                "f" -> {
                    if (parts.size < 4) throw ObjException("面至少需要 3 个顶点: \"$line\"")
                    if (!uvDecided) {
                        hasUv = parts[1].contains('/') &&
                            parts[1].split("/").getOrNull(1)?.isNotEmpty() == true
                        uvDecided = true
                    }
                    // 解析面各顶点 → 输出索引
                    val faceIdx = IntArray(parts.size - 1)
                    for (i in 1 until parts.size) {
                        val seg = parts[i].split("/")
                        val vi = toIndex(seg[0], posList.size)
                        val ti = if (seg.size > 1 && seg[1].isNotEmpty()) toIndex(seg[1], uvList.size) else -1
                        val ni = if (seg.size > 2 && seg[2].isNotEmpty()) toIndex(seg[2], nrmList.size) else -1
                        // 组合键打包为 Long（各占 21 位，ti/ni 各 +1 避开 -1）
                        val key = (vi.toLong() shl 42) or
                            ((ti + 1).toLong() shl 21) or
                            (ni + 1).toLong()
                        val existing = vertexMap[key]
                        if (existing != null) {
                            faceIdx[i - 1] = existing
                        } else {
                            val newIndex = outPos.size / 3
                            if (newIndex >= MAX_OUT_VERTICES) {
                                throw ObjException("合并后顶点数超过上限 $MAX_OUT_VERTICES，文件过大无法在手机上替换")
                            }
                            if (vi >= posList.size) throw ObjException("顶点索引越界: ${seg[0]}")
                            val p = posList[vi]
                            outPos.add(p[0]); outPos.add(p[1]); outPos.add(p[2])
                            if (ni >= 0) {
                                if (ni >= nrmList.size) throw ObjException("法线索引越界: ${seg[2]}")
                                val n = nrmList[ni]
                                outNrm.add(n[0]); outNrm.add(n[1]); outNrm.add(n[2])
                            } else {
                                outNrm.add(0f); outNrm.add(0f); outNrm.add(0f)
                            }
                            if (hasUv) {
                                if (ti < 0) {
                                    outUv.add(0f); outUv.add(0f)
                                } else {
                                    if (ti >= uvList.size) throw ObjException("UV 索引越界: ${seg[1]}")
                                    val t = uvList[ti]
                                    outUv.add(t[0]); outUv.add(t[1])
                                }
                            }
                            vertexMap[key] = newIndex
                            faceIdx[i - 1] = newIndex
                        }
                    }
                    // 扇形三角化
                    for (i in 1 until faceIdx.size - 1) {
                        outIdx.add(faceIdx[0])
                        outIdx.add(faceIdx[i])
                        outIdx.add(faceIdx[i + 1])
                    }
                    groupFaceCount++
                }
            }
        }
        closeGroup(outIdx.size)

        if (outIdx.isEmpty()) throw ObjException("OBJ 中没有可用的面数据")
        if (outPos.isEmpty()) throw ObjException("OBJ 中没有顶点数据")

        val positions = outPos.toFloatArray()
        var normals = outNrm.toFloatArray()
        val uvs = if (hasUv) outUv.toFloatArray() else null
        val indices = outIdx.toIntArray()

        // 法线缺失（所有分量均为 0）→ 按面法线累加平滑
        if (normals.all { it == 0f }) {
            normals = computeSmoothNormals(positions, indices)
        }

        // 包围盒
        val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val max = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        var vi = 0
        while (vi < positions.size) {
            for (a in 0 until 3) {
                val v = positions[vi + a]
                if (v < min[a]) min[a] = v
                if (v > max[a]) max[a] = v
            }
            vi += 3
        }
        // 空网格保护
        for (a in 0 until 3) {
            if (min[a] > max[a]) { min[a] = 0f; max[a] = 0f }
        }

        return ObjMeshData(positions, normals, uvs, indices, groups, min, max)
    }

    /** 面法线累加 + 归一化的平滑法线 */
    private fun computeSmoothNormals(positions: FloatArray, indices: IntArray): FloatArray {
        val normals = FloatArray(positions.size)
        for (t in indices.indices step 3) {
            val i0 = indices[t] * 3
            val i1 = indices[t + 1] * 3
            val i2 = indices[t + 2] * 3
            // e1 × e2
            val e1x = positions[i1] - positions[i0]
            val e1y = positions[i1 + 1] - positions[i0 + 1]
            val e1z = positions[i1 + 2] - positions[i0 + 2]
            val e2x = positions[i2] - positions[i0]
            val e2y = positions[i2 + 1] - positions[i0 + 1]
            val e2z = positions[i2 + 2] - positions[i0 + 2]
            val nx = e1y * e2z - e1z * e2y
            val ny = e1z * e2x - e1x * e2z
            val nz = e1x * e2y - e1y * e2x
            for (base in intArrayOf(i0, i1, i2)) {
                normals[base] += nx
                normals[base + 1] += ny
                normals[base + 2] += nz
            }
        }
        var i = 0
        while (i < normals.size) {
            val x = normals[i]; val y = normals[i + 1]; val z = normals[i + 2]
            val len = kotlin.math.sqrt(x * x + y * y + z * z)
            if (len > 1e-12f) {
                normals[i] = x / len
                normals[i + 1] = y / len
                normals[i + 2] = z / len
            } else {
                normals[i] = 0f; normals[i + 1] = 1f; normals[i + 2] = 0f
            }
            i += 3
        }
        return normals
    }
}

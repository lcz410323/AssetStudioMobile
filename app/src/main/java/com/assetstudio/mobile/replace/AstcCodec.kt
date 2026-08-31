package com.assetstudio.mobile.replace

import kotlin.math.abs
import kotlin.math.sqrt

/*
 * ASTC LDR 编码器 + 软件解码器（Khronos ASTC 规范实现）。
 *
 * 块尺寸：4x4 / 5x5 / 6x6 / 8x8；编码路径：单分区 + 单权重平面 + CEM 12
 * （LDR RGBA direct）。已用独立解码器 texture2ddecoder 交叉验证（28/28 通过）。
 *
 * 与解码器行为逐位对齐的关键点：
 * 1. 块模式（11 位）字面量，均经解码器 decode_block_params 逐位验证：
 *    | footprint | grid | 权重          | 模式  |
 *    | 4x4       | 4x3  | 4b  (0..15)   | 0x222 |
 *    | 5x5       | 5x3  | 1T2b (0..11)  | 0x2B1 |
 *    | 6x6       | 6x3  | 3b  (0..7)    | 0x133 |
 *    | 8x8       | 8x3  | 2b  (0..3)    | 0x026 |
 * 2. 端点量化范围由剩余位隐式推断（解码器按同一规则探测）：
 *    remaining = 128 - 17 - 权重流位数，19 个范围按精度降序取第一个放得下的
 *    → 4x4/8x8 剩余 63 位 → 1T6b（192 级）；5x5/6x6 剩余 57 位 → 7b（128 级）。
 * 3. ISE 部分组（值数非 5/3 整除）只写解码器实际读取的位
 *    （末块位数 = ceil(块位数×k/5)，恰好是 k 个值的 m 位 + T/Q 位），
 *    且部分组 T/Q 字对缺失位任意取值鲁棒（解码器把它们当 0 或垃圾都不影响前 k 个值）。
 * 4. 纯位权重解量化：位重复到 6 位（4b: v<<2|v>>2；3b: v<<3|v；2b: v<<4|v<<2|v），
 *    结果 >32 再 +1 → 0..64。
 * 5. 端点用 PCA 主轴选取（亮度法对"等亮度彩色渐变"（r 升 g 降）退化）。
 * 6. CEM12 直接分支要求 s1 >= s0（解量化后的 RGB 和），否则解码器走 blue-contract。
 * 7. 权重流自块顶向下生长（stream bit i = block bit 127-i），端点流自 bit 17 向上。
 * 纯色块走 void-extent 编码（恒定色 UNORM16，位精确）。
 */
object AstcCodec {

    // ============================ 块配置 ============================

    /** 每种块尺寸的编码参数 */
    class BlockConfig(
        val footprintW: Int,
        val footprintH: Int,
        val gridW: Int,
        val gridH: Int,
        /** block mode 11 位（bits[10..0]） */
        val blockMode: Int,
        /** 权重 ISE 参数 */
        val weightTrits: Int,   // 0 或 1
        val weightQuints: Int,  // 0 或 1
        val weightBits: Int,    // 每值附加位数
        val weightMax: Int      // 权重值域上限（含）
    ) {
        val weightCount: Int get() = gridW * gridH

        /** 权重流位数（规范公式 n*b + ceil(n*8/5) / ceil(n*7/3)，与解码器 weight_bits 一致） */
        val weightStreamBits: Int = run {
            val n = weightCount
            when {
                weightTrits == 1 -> n * weightBits + (n * 8 + 4) / 5
                weightQuints == 1 -> n * weightBits + (n * 7 + 2) / 3
                else -> n * weightBits
            }
        }
    }

    /** 四种块尺寸的配置表 */
    val configs: Map<Int, BlockConfig> = mapOf(
        4 to BlockConfig(4, 4, 4, 3, 0x222, 0, 0, 4, 15),
        5 to BlockConfig(5, 5, 5, 3, 0x2B1, 1, 0, 2, 11),
        6 to BlockConfig(6, 6, 6, 3, 0x133, 0, 0, 3, 7),
        8 to BlockConfig(8, 8, 8, 3, 0x026, 0, 0, 2, 3)
    )

    private const val CONFIG_BITS = 17   // 11 块模式 + 2 分区数 + 4 CEM（单分区）
    private const val CEM = 12           // LDR RGBA direct
    private const val NUM_EP_VALUES = 8  // CEM12 → 8 个端点值

    // ============================ 端点量化范围（规范表 C.2.8） ============================

    /** (trits, quints, bits, m 位名, B 参数 9 位串, C)；按精度降序 = 解码器探测顺序 */
    private class EpRange(
        val trits: Int,
        val quints: Int,
        val bits: Int,
        /** m 值各位的名字（MSB→LSB 书写，'a' 恒为 bit0/LSB） */
        val layout: String,
        /** B 参数 9 位串（MSB→LSB，i=0 → bit8）；纯位范围为 null */
        val bPattern: String?,
        val c: Int
    )

    private val EP_RANGES = arrayOf(
        EpRange(0, 0, 8, "", null, 0),               // 0: 256 级 8b
        EpRange(1, 0, 6, "fedcba", "fedcb000f", 5),  // 1: 192 级 1T6b
        EpRange(0, 1, 5, "edcba", "edcb0000e", 6),   // 2: 160 级 1Q5b
        EpRange(0, 0, 7, "", null, 0),               // 3: 128 级 7b
        EpRange(1, 0, 5, "edcba", "edcb000ed", 11),  // 4: 96 级 1T5b
        EpRange(0, 1, 4, "dcba", "dcb0000dc", 13),   // 5: 80 级 1Q4b
        EpRange(0, 0, 6, "", null, 0),               // 6: 64 级 6b
        EpRange(1, 0, 4, "dcba", "dcb000dcb", 22),   // 7: 48 级 1T4b
        EpRange(0, 1, 3, "cba", "cb0000cbc", 26),    // 8: 40 级 1Q3b
        EpRange(0, 0, 5, "", null, 0),               // 9: 32 级 5b
        EpRange(1, 0, 3, "cba", "cb000cbcb", 44),    // 10: 24 级 1T3b
        EpRange(0, 1, 2, "ba", "b0000bb00", 54),     // 11: 20 级 1Q2b
        EpRange(0, 0, 4, "", null, 0),               // 12: 16 级 4b
        EpRange(1, 0, 2, "ba", "b000b0bb0", 93),     // 13: 12 级 1T2b
        EpRange(0, 1, 1, "a", "000000000", 113),     // 14: 10 级 1Q1b
        EpRange(0, 0, 3, "", null, 0),               // 15: 8 级 3b
        EpRange(1, 0, 1, "a", "000000000", 204),     // 16: 6 级 1T1b
        EpRange(0, 0, 2, "", null, 0),               // 17: 4 级 2b
        EpRange(0, 0, 1, "", null, 0)                // 18: 2 级 1b
    )

    /** 该范围编码 n 个值的 ISE 位数（规范公式，与解码器 endpoint_bits 计算一致） */
    internal fun epRangeIseBits(nValues: Int, rangeIdx: Int): Int {
        val r = EP_RANGES[rangeIdx]
        var b = nValues * r.bits
        if (r.trits == 1) b += (nValues * 8 + 4) / 5
        if (r.quints == 1) b += (nValues * 7 + 2) / 3
        return b
    }

    /** 解码器同规则：从高精度到低精度取第一个放得下的范围 */
    internal fun selectEpRange(nValues: Int, remainingBits: Int): Int {
        for (r in EP_RANGES.indices) {
            if (epRangeIseBits(nValues, r) <= remainingBits) return r
        }
        return EP_RANGES.size - 1
    }

    private val epUnquantCache = arrayOfNulls<IntArray>(EP_RANGES.size)
    private val epQuantCache = arrayOfNulls<IntArray>(EP_RANGES.size)

    /** 该范围的端点解量化表：存储值 → 0..255 */
    internal fun epUnquantTable(rangeIdx: Int): IntArray {
        synchronized(epUnquantCache) {
            var t = epUnquantCache[rangeIdx]
            if (t == null) {
                t = buildEpUnquant(rangeIdx)
                epUnquantCache[rangeIdx] = t
            }
            return t
        }
    }

    /** 该范围的端点量化表：0..255 → 最近存储值 */
    private fun epQuantTable(rangeIdx: Int): IntArray {
        synchronized(epQuantCache) {
            var t = epQuantCache[rangeIdx]
            if (t == null) {
                val unq = epUnquantTable(rangeIdx)
                t = IntArray(256)
                for (target in 0..255) {
                    var best = 0
                    var bestDist = Int.MAX_VALUE
                    for (v in unq.indices) {
                        val d = abs(unq[v] - target)
                        if (d < bestDist) { bestDist = d; best = v }
                    }
                    t[target] = best
                }
                epQuantCache[rangeIdx] = t
            }
            return t
        }
    }

    /** 位重复：bits 位值按 MSB 起重复到 width 位 */
    private fun replicateBits(v: Int, bits: Int, width: Int): Int {
        var r = 0
        for (i in 0 until width) {
            val src = (bits - 1) - (i % bits)
            r = (r shl 1) or ((v shr src) and 1)
        }
        return r
    }

    /** 端点解量化（规范 Endpoint Unquantization：A=aaaaaaaaa(9b)，B/C/D 按范围查表） */
    private fun buildEpUnquant(rangeIdx: Int): IntArray {
        val r = EP_RANGES[rangeIdx]
        if (r.trits == 0 && r.quints == 0) {
            val table = IntArray(1 shl r.bits)
            for (m in table.indices) {
                table[m] = if (r.bits == 8) m else replicateBits(m, r.bits, 8)
            }
            return table
        }
        val dmax = if (r.trits == 1) 3 else 5
        // 字母 → m 中的位序（layout MSB→LSB 书写，'a' 在末尾 = bit0）
        val letterPos = HashMap<Char, Int>(r.layout.length)
        for (i in r.layout.indices) {
            letterPos[r.layout[i]] = r.layout.length - 1 - i
        }
        val table = IntArray(dmax shl r.bits)
        for (d in 0 until dmax) {
            for (m in 0 until (1 shl r.bits)) {
                val a = (m shr (letterPos['a'] ?: 0)) and 1
                val bigA = if (a == 1) 0x1FF else 0
                var b = 0
                val pat = r.bPattern!!
                for (i in pat.indices) {
                    val pos = letterPos[pat[i]]
                    if (pos != null && ((m shr pos) and 1) == 1) {
                        b = b or (1 shl (8 - i))
                    }
                }
                var v = d * r.c + b
                v = v xor bigA
                v = (bigA and 0x80) or (v ushr 2)
                table[(d shl r.bits) or m] = v
            }
        }
        return table
    }

    // ============================ ISE 编解码 ============================

    /*
     * Trit 打包：5 个 trit (t0..t4) 编码为 8 位 T[7:0]。
     * 规范只定义解码；这里对全部 256 个 T 值执行解码算法，
     * 建 trit 五元组 → T 的逆向表（243 项，位级保证一致）。
     */
    private val tritEncodeTable: HashMap<Int, Int> = buildTritEncodeTable()
    private val quintEncodeTable: HashMap<Int, Int> = buildQuintEncodeTable()

    /** 规范 trit 解码：T[7:0] → (t0,t1,t2,t3,t4) */
    internal fun decodeTrits(t: Int): IntArray {
        val t0: Int; val t1: Int; val t2: Int; val t3: Int; val t4: Int
        val c: Int
        if (((t shr 2) and 7) == 7) {
            c = ((t shr 5) shl 2) or (t and 3)
            t4 = 2; t3 = 2
        } else {
            c = t and 0x1F
            if (((t shr 5) and 3) == 3) {
                t4 = 2; t3 = (t shr 7) and 1
            } else {
                t4 = (t shr 7) and 1; t3 = (t shr 5) and 3
            }
        }
        when {
            (c and 3) == 3 -> {
                t2 = 2; t1 = (c shr 4) and 1
                // t0 = { C[3], C[2]&~C[3] }：2 位拼接（bit1=C[3], bit0=C[2]&~C[3]）
                t0 = (((c shr 3) and 1) shl 1) or (((c shr 2) and 1) and inv((c shr 3) and 1))
            }
            ((c shr 2) and 3) == 3 -> {
                t2 = 2; t1 = 2; t0 = c and 3
            }
            else -> {
                t2 = (c shr 4) and 1; t1 = (c shr 2) and 3
                // t0 = { C[1], C[0]&~C[1] }：2 位拼接（bit1=C[1], bit0=C[0]&~C[1]）
                t0 = (((c shr 1) and 1) shl 1) or ((c and 1) and inv((c shr 1) and 1))
            }
        }
        return intArrayOf(t0, t1, t2, t3, t4)
    }

    /** 规范 quint 解码：Q[6:0] → (q0,q1,q2) */
    internal fun decodeQuints(q: Int): IntArray {
        val q0: Int; val q1: Int; val q2: Int
        val c: Int
        if (((q shr 1) and 3) == 3 && ((q shr 5) and 3) == 0) {
            // q2 = {Q[0], Q[4]&~Q[0], Q[3]&~Q[0]}（3 位）
            q2 = ((q and 1) shl 2) or
                ((((q shr 4) and 1) and inv(q and 1)) shl 1) or
                (((q shr 3) and 1) and inv(q and 1))
            q1 = 4; q0 = 4
        } else {
            if (((q shr 1) and 3) == 3) {
                q2 = 4
                // C = {Q[4:3], ~Q[6:5], Q[0]}（5 位：bit4=Q4 bit3=Q3 bit2=~Q6 bit1=~Q5 bit0=Q0）
                c = (((q shr 4) and 1) shl 4) or
                    (((q shr 3) and 1) shl 3) or
                    (inv((q shr 6) and 1) shl 2) or
                    (inv((q shr 5) and 1) shl 1) or
                    (q and 1)
            } else {
                q2 = (q shr 5) and 3
                c = q and 0x1F
            }
            if ((c and 7) == 5) {
                q1 = 4; q0 = (c shr 3) and 3
            } else {
                q1 = (c shr 3) and 3; q0 = c and 7
            }
        }
        return intArrayOf(q0, q1, q2)
    }

    private fun inv(v: Int) = v xor 1

    private fun buildTritEncodeTable(): HashMap<Int, Int> {
        val map = HashMap<Int, Int>(243)
        for (t in 0..255) {
            val trits = decodeTrits(t)
            val key = trits[0] + (trits[1] shl 3) + (trits[2] shl 6) + (trits[3] shl 9) + (trits[4] shl 12)
            if (!map.containsKey(key)) map[key] = t
        }
        return map
    }

    private fun buildQuintEncodeTable(): HashMap<Int, Int> {
        val map = HashMap<Int, Int>(125)
        for (q in 0..127) {
            val quints = decodeQuints(q)
            val key = quints[0] + (quints[1] shl 3) + (quints[2] shl 6)
            if (!map.containsKey(key)) map[key] = q
        }
        return map
    }

    /** 每个值在 T/Q 字中的位序号（规范 ISE 块内布局） */
    private val TRIT_BIT_POS = arrayOf(
        intArrayOf(0, 1), intArrayOf(2, 3), intArrayOf(4), intArrayOf(5, 6), intArrayOf(7)
    )
    private val QUINT_BIT_POS = arrayOf(
        intArrayOf(0, 1, 2), intArrayOf(3, 4), intArrayOf(5, 6)
    )

    // ---- 部分组鲁棒表（懒构建） ----

    private val partialTritTables = arrayOfNulls<HashMap<Int, Int>>(5)   // index = k（1..4）
    private val partialQuintTables = arrayOfNulls<HashMap<Int, Int>>(3)  // index = k（1..2）

    private fun partialTritTable(k: Int): HashMap<Int, Int> {
        synchronized(partialTritTables) {
            var t = partialTritTables[k]
            if (t == null) {
                t = buildPartialTritTable(k)
                partialTritTables[k] = t
            }
            return t
        }
    }

    private fun partialQuintTable(k: Int): HashMap<Int, Int> {
        synchronized(partialQuintTables) {
            var t = partialQuintTables[k]
            if (t == null) {
                t = buildPartialQuintTable(k)
                partialQuintTables[k] = t
            }
            return t
        }
    }

    private fun pow3(n: Int): Int {
        var r = 1
        repeat(n) { r *= 3 }
        return r
    }

    private fun pow5(n: Int): Int {
        var r = 1
        repeat(n) { r *= 5 }
        return r
    }

    /** 部分组（k<5 个 trit）鲁棒 T 字表：缺失位解码时为任意值，保证前 k 个 trit 不变 */
    private fun buildPartialTritTable(k: Int): HashMap<Int, Int> {
        val written = BooleanArray(8)
        for (i in 0 until k) for (p in TRIT_BIT_POS[i]) written[p] = true
        val missing = ArrayList<Int>()
        for (b in 0 until 8) if (!written[b]) missing.add(b)
        val nMask = 1 shl missing.size
        val table = HashMap<Int, Int>()
        for (target in 0 until pow3(k)) {
            val ts = IntArray(k) { i -> (target / pow3(i)) % 3 }
            var found = -1
            outer@ for (base in 0..255) {
                for (mask in 0 until nMask) {
                    var tw = base
                    for (mi in missing.indices) {
                        val mb = missing[mi]
                        tw = if ((mask shr mi) and 1 == 1) tw or (1 shl mb) else tw and (1 shl mb).inv()
                    }
                    val dec = decodeTrits(tw)
                    for (i in 0 until k) if (dec[i] != ts[i]) continue@outer
                }
                found = base
                break
            }
            check(found >= 0) { "部分组 trit 编码失败: k=$k ts=${ts.contentToString()}" }
            var key = 0
            for (i in 0 until k) key = key or (ts[i] shl (3 * i))
            table[key] = found
        }
        return table
    }

    /** 部分组（k<3 个 quint）鲁棒 Q 字表（个别组合可能不可鲁棒编码，调用方需处理） */
    private fun buildPartialQuintTable(k: Int): HashMap<Int, Int> {
        val written = BooleanArray(7)
        for (i in 0 until k) for (p in QUINT_BIT_POS[i]) written[p] = true
        val missing = ArrayList<Int>()
        for (b in 0 until 7) if (!written[b]) missing.add(b)
        val nMask = 1 shl missing.size
        val table = HashMap<Int, Int>()
        for (target in 0 until pow5(k)) {
            val qs = IntArray(k) { i -> (target / pow5(i)) % 5 }
            var found = -1
            outer@ for (base in 0..127) {
                for (mask in 0 until nMask) {
                    var qw = base
                    for (mi in missing.indices) {
                        val mb = missing[mi]
                        qw = if ((mask shr mi) and 1 == 1) qw or (1 shl mb) else qw and (1 shl mb).inv()
                    }
                    val dec = decodeQuints(qw)
                    for (i in 0 until k) if (dec[i] != qs[i]) continue@outer
                }
                found = base
                break
            }
            if (found < 0) continue
            var key = 0
            for (i in 0 until k) key = key or (qs[i] shl (3 * i))
            table[key] = found
        }
        return table
    }

    // ============================ 位流工具 ============================

    /** 位流写入器（LSB-first，与块位序一致） */
    private class BitWriter(capacityBits: Int) {
        val data = ByteArray((capacityBits + 7) / 8)
        var bitPos = 0

        fun write(value: Int, numBits: Int) {
            for (i in 0 until numBits) {
                if ((value shr i) and 1 == 1) {
                    val p = bitPos + i
                    data[p ushr 3] = (data[p ushr 3].toInt() or (1 shl (p and 7))).toByte()
                }
            }
            bitPos += numBits
        }
    }

    /** 位流读取器（LSB-first，支持绝对块内位偏移） */
    private class BitReader(val data: ByteArray, var bitPos: Int = 0) {
        fun read(numBits: Int): Int {
            var v = 0
            for (i in 0 until numBits) {
                val p = bitPos + i
                val bit = (data[p ushr 3].toInt() shr (p and 7)) and 1
                v = v or (bit shl i)
            }
            bitPos += numBits
            return v
        }
    }

    // ============================ ISE 序列读写 ============================

    /**
     * ISE 序列写入（部分组感知）：
     * 完整组写全部位；末尾部分组只写 k 个值的 m 位与 T/Q 位
     * （与解码器读取的末块位数 ceil(块位数×k/5) 一致）。
     */
    private fun writeIseSequence(
        values: IntArray,
        trits: Int, quints: Int, bitsPerValue: Int,
        writer: BitWriter
    ) {
        val n = values.size
        val mask = (1 shl bitsPerValue) - 1
        when {
            trits == 1 -> {
                var b = 0
                while (b * 5 < n) {
                    val k = minOf(5, n - b * 5)
                    val tWord: Int = if (k == 5) {
                        var key = 0
                        for (i in 0 until 5) key = key or ((values[b * 5 + i] ushr bitsPerValue) shl (3 * i))
                        tritEncodeTable[key]
                            ?: throw IllegalStateException("trit 编码表缺失: key=$key")
                    } else {
                        var key = 0
                        for (i in 0 until k) key = key or ((values[b * 5 + i] ushr bitsPerValue) shl (3 * i))
                        partialTritTable(k)[key]
                            ?: throw IllegalStateException("部分组 trit 编码失败: k=$k key=$key")
                    }
                    for (i in 0 until k) {
                        writer.write(values[b * 5 + i] and mask, bitsPerValue)
                        for (p in TRIT_BIT_POS[i]) {
                            writer.write((tWord shr p) and 1, 1)
                        }
                    }
                    b++
                }
            }
            quints == 1 -> {
                var b = 0
                while (b * 3 < n) {
                    val k = minOf(3, n - b * 3)
                    val qWord: Int = if (k == 3) {
                        var key = 0
                        for (i in 0 until 3) key = key or ((values[b * 3 + i] ushr bitsPerValue) shl (3 * i))
                        quintEncodeTable[key]
                            ?: throw IllegalStateException("quint 编码表缺失: key=$key")
                    } else {
                        var key = 0
                        for (i in 0 until k) key = key or ((values[b * 3 + i] ushr bitsPerValue) shl (3 * i))
                        partialQuintTable(k)[key]
                            ?: throw IllegalStateException("部分组 quint 不可鲁棒编码: k=$k key=$key")
                    }
                    for (i in 0 until k) {
                        writer.write(values[b * 3 + i] and mask, bitsPerValue)
                        for (p in QUINT_BIT_POS[i]) {
                            writer.write((qWord shr p) and 1, 1)
                        }
                    }
                    b++
                }
            }
            else -> {
                for (v in values) writer.write(v, bitsPerValue)
            }
        }
    }

    /**
     * ISE 序列读取（与 texture2ddecoder decode_intseq 一致）：
     * 按 5 值（trit）/ 3 值（quint）分块，末尾部分组只读 k 个值的位，
     * 未读到的 T/Q 位按 0（编码端的部分组鲁棒表已覆盖该情况）。
     */
    private fun readIseSequence(
        count: Int,
        trits: Int, quints: Int, bitsPerValue: Int,
        reader: BitReader
    ): IntArray {
        val out = IntArray(count)
        when {
            trits == 1 -> {
                var n = 0
                while (n < count) {
                    val k = minOf(5, count - n)
                    val ms = IntArray(k)
                    var tWord = 0
                    for (i in 0 until k) {
                        ms[i] = reader.read(bitsPerValue)
                        for (p in TRIT_BIT_POS[i]) {
                            tWord = tWord or (reader.read(1) shl p)
                        }
                    }
                    val ts = decodeTrits(tWord)
                    for (i in 0 until k) {
                        out[n] = (ts[i] shl bitsPerValue) or ms[i]
                        n++
                    }
                }
            }
            quints == 1 -> {
                var n = 0
                while (n < count) {
                    val k = minOf(3, count - n)
                    val ms = IntArray(k)
                    var qWord = 0
                    for (i in 0 until k) {
                        ms[i] = reader.read(bitsPerValue)
                        for (p in QUINT_BIT_POS[i]) {
                            qWord = qWord or (reader.read(1) shl p)
                        }
                    }
                    val qs = decodeQuints(qWord)
                    for (i in 0 until k) {
                        out[n] = (qs[i] shl bitsPerValue) or ms[i]
                        n++
                    }
                }
            }
            else -> {
                for (i in 0 until count) out[i] = reader.read(bitsPerValue)
            }
        }
        return out
    }

    // ============================ 权重解量化 ============================

    /** 权重解量化：存储值 → 0..64（纯位权重按 6 位重复 + >32 加 1，与解码器一致） */
    fun unquantWeight(v: Int, cfg: BlockConfig): Int {
        val unq: Int = when {
            cfg.weightQuints == 1 -> {
                val d = v shr cfg.weightBits
                val m = v and ((1 shl cfg.weightBits) - 1)
                unquantWeightTQ(m, d, cfg.weightBits, isQuint = true)
            }
            cfg.weightTrits == 1 -> {
                val t = v shr cfg.weightBits
                val m = v and ((1 shl cfg.weightBits) - 1)
                unquantWeightTQ(m, t, cfg.weightBits, isQuint = false)
            }
            else -> replicateBits(v, cfg.weightBits, 6)   // 6 位重复（解码器行为）
        }
        return if (unq > 32) unq + 1 else unq
    }

    /** trit/quint 权重解量化（规范参数表：A=aaaaaaa B/C/D 按位数，与解码器 decode_weights 一致） */
    private fun unquantWeightTQ(m: Int, d: Int, bits: Int, isQuint: Boolean): Int {
        return when {
            isQuint && bits == 1 -> { // 0..9: A=aaaaaaa B=0000000 C=28 D=quint
                val a7 = if (m and 1 == 1) 0x7F else 0
                var unq = d * 28
                unq = unq xor a7
                (a7 and 0x20) or (unq shr 2)
            }
            !isQuint && bits == 1 -> { // 0..5: A=aaaaaaa B=0000000 C=50 D=trit
                val a7 = if (m and 1 == 1) 0x7F else 0
                var unq = d * 50
                unq = unq xor a7
                (a7 and 0x20) or (unq shr 2)
            }
            !isQuint && bits == 2 -> { // 0..11: A=aaaaaaa B=b000b0b C=23 D=trit
                val a = m and 1
                val b = (m shr 1) and 1
                val a7 = if (a == 1) 0x7F else 0
                val b7 = (b shl 6) or (b shl 2) or b
                var unq = d * 23 + b7
                unq = unq xor a7
                (a7 and 0x20) or (unq shr 2)
            }
            else -> throw IllegalArgumentException("unsupported weight range bits=$bits quint=$isQuint")
        }
    }

    private val weightQuantCache = HashMap<Int, IntArray>()

    /** 权重量化表：插值权重 0..64 → 最近存储值 */
    internal fun weightQuantTable(cfg: BlockConfig): IntArray {
        synchronized(weightQuantCache) {
            return weightQuantCache.getOrPut(cfg.blockMode) {
                val table = IntArray(65)
                for (w in 0..64) {
                    var best = 0
                    var bestDist = Int.MAX_VALUE
                    for (v in 0..cfg.weightMax) {
                        val d = abs(unquantWeight(v, cfg) - w)
                        if (d < bestDist) { bestDist = d; best = v }
                    }
                    table[w] = best
                }
                table
            }
        }
    }

    // ============================ PCA 端点选择 ============================

    /**
     * PCA 主轴端点选择：协方差主方向上投影极值（合成端点）。
     * 亮度法在"亮度恒定的彩色渐变"（r 升 g 降）下退化，PCA 无此问题。
     * @return (e0, e1) 各 4 通道 0..255；零方差返回 null
     */
    private fun selectEndpointsPca(blockPixels: IntArray): Pair<IntArray, IntArray>? {
        val n = blockPixels.size
        val mean = DoubleArray(4)
        for (p in blockPixels) {
            mean[0] += (p ushr 16) and 0xFF
            mean[1] += (p ushr 8) and 0xFF
            mean[2] += p and 0xFF
            mean[3] += (p ushr 24) and 0xFF
        }
        for (c in 0 until 4) mean[c] /= n
        // 协方差 4x4
        val cov = Array(4) { DoubleArray(4) }
        for (p in blockPixels) {
            val d = doubleArrayOf(
                ((p ushr 16) and 0xFF) - mean[0],
                ((p ushr 8) and 0xFF) - mean[1],
                (p and 0xFF) - mean[2],
                ((p ushr 24) and 0xFF) - mean[3]
            )
            for (i in 0 until 4) {
                for (j in 0 until 4) cov[i][j] += d[i] * d[j]
            }
        }
        // 幂迭代求主特征向量（16 轮足够收敛）
        var v = doubleArrayOf(1.0, 0.5, 0.25, 0.125)
        for (iter in 0 until 16) {
            val w = DoubleArray(4)
            for (i in 0 until 4) {
                for (j in 0 until 4) w[i] += cov[i][j] * v[j]
            }
            var norm = 0.0
            for (x in w) norm += x * x
            norm = sqrt(norm)
            if (norm < 1e-9) return null
            for (i in 0 until 4) v[i] = w[i] / norm
        }
        // 投影极值 → 合成端点（限幅 0..255）
        var smin = Double.MAX_VALUE
        var smax = -Double.MAX_VALUE
        for (p in blockPixels) {
            var s = 0.0
            s += (((p ushr 16) and 0xFF) - mean[0]) * v[0]
            s += (((p ushr 8) and 0xFF) - mean[1]) * v[1]
            s += ((p and 0xFF) - mean[2]) * v[2]
            s += (((p ushr 24) and 0xFF) - mean[3]) * v[3]
            if (s < smin) smin = s
            if (s > smax) smax = s
        }
        val e0 = IntArray(4) { c -> roundPy(mean[c] + v[c] * smin).coerceIn(0, 255) }
        val e1 = IntArray(4) { c -> roundPy(mean[c] + v[c] * smax).coerceIn(0, 255) }
        return Pair(e0, e1)
    }

    /** Python int(round(x)) 兼容：四舍六入五成双（黄金向量对齐用） */
    private fun roundPy(x: Double): Int {
        val floor = kotlin.math.floor(x)
        val diff = x - floor
        return when {
            diff < 0.5 -> floor.toInt()
            diff > 0.5 -> floor.toInt() + 1
            else -> if (floor.toLong() % 2 == 0L) floor.toInt() else floor.toInt() + 1
        }
    }

    // ============================ 图像编码 ============================

    /**
     * 编码一张图（0xAARRGGBB 像素）为 ASTC 数据。
     * @param blockFootprint 块尺寸（4/5/6/8，正方形）
     * @return ASTC 压缩字节
     */
    fun encode(pixels: IntArray, width: Int, height: Int, blockFootprint: Int): ByteArray {
        val cfg = configs[blockFootprint]
            ?: throw IllegalArgumentException("unsupported footprint $blockFootprint (支持 4/5/6/8)")
        if (width <= 0 || height <= 0) throw IllegalArgumentException("invalid size ${width}x$height")

        val blocksW = (width + cfg.footprintW - 1) / cfg.footprintW
        val blocksH = (height + cfg.footprintH - 1) / cfg.footprintH
        val out = ByteArray(blocksW * blocksH * 16)

        for (by in 0 until blocksH) {
            for (bx in 0 until blocksW) {
                val block = readBlockPixels(pixels, width, height, bx, by, cfg)
                val encoded = encodeBlock(block, cfg)
                System.arraycopy(encoded, 0, out, (by * blocksW + bx) * 16, 16)
            }
        }
        return out
    }

    /** 读取一个块的像素（边缘填充），返回 footprintW*footprintH 个 ARGB */
    private fun readBlockPixels(
        pixels: IntArray, width: Int, height: Int,
        bx: Int, by: Int, cfg: BlockConfig
    ): IntArray {
        val block = IntArray(cfg.footprintW * cfg.footprintH)
        for (y in 0 until cfg.footprintH) {
            val sy = (by * cfg.footprintH + y).coerceAtMost(height - 1)
            for (x in 0 until cfg.footprintW) {
                val sx = (bx * cfg.footprintW + x).coerceAtMost(width - 1)
                block[y * cfg.footprintW + x] = pixels[sy * width + sx]
            }
        }
        return block
    }

    /** 编码单个 16 字节块 */
    internal fun encodeBlock(blockPixels: IntArray, cfg: BlockConfig): ByteArray {
        // ---------- 0. 纯色块 → void extent（位精确） ----------
        val first = blockPixels[0]
        var allSame = true
        for (p in blockPixels) if (p != first) { allSame = false; break }
        if (allSame) return encodeVoidExtent(first)

        // ---------- 1. 端点量化范围（必须与解码器推断一致） ----------
        val remaining = 128 - CONFIG_BITS - cfg.weightStreamBits
        val epRange = selectEpRange(NUM_EP_VALUES, remaining)
        val range = EP_RANGES[epRange]
        val epUnquant = epUnquantTable(epRange)
        val epQuant = epQuantTable(epRange)

        // ---------- 2. 端点选择：PCA 主轴 ----------
        var e0Raw: IntArray
        var e1Raw: IntArray
        val ep = selectEndpointsPca(blockPixels)
        if (ep != null) {
            e0Raw = ep.first
            e1Raw = ep.second
        } else {
            // 退化（零方差）：用首像素
            e0Raw = intArrayOf(r8(first), g8(first), b8(first), a8(first))
            e1Raw = e0Raw.copyOf()
        }

        var e0q = IntArray(4) { epQuant[e0Raw[it].coerceIn(0, 255)] }
        var e1q = IntArray(4) { epQuant[e1Raw[it].coerceIn(0, 255)] }
        var e0u = IntArray(4) { epUnquant[e0q[it]] }
        var e1u = IntArray(4) { epUnquant[e1q[it]] }
        // CEM12 直接分支要求 s1 >= s0（解量化后的 RGB 和）
        if (e1u[0] + e1u[1] + e1u[2] < e0u[0] + e0u[1] + e0u[2]) {
            val tq = e0q; e0q = e1q; e1q = tq
            val tu = e0u; e0u = e1u; e1u = tu
        }
        if (e1u[0] + e1u[1] + e1u[2] < e0u[0] + e0u[1] + e0u[2]) {
            // 极罕见（量化扰乱顺序）：把 e1 的 RGB 提到最大量化值
            val maxv = epQuant[255]
            e1q = intArrayOf(maxv, maxv, maxv, e1q[3])
            e1u = IntArray(4) { epUnquant[e1q[it]] }
        }

        // ---------- 3. 权重：逆 infill ----------
        // 解码端用双线性系数把 grid 权重插值到每个像素（规范 Weight Infill）；
        // 编码端反向操作：把每个像素的理想权重按相同系数分摊回 4 个相邻 grid 点，
        // 加权平均后量化。理想权重 = 像素在 e0→e1 连线上的投影 × 64。
        val quantTable = weightQuantTable(cfg)
        val dx = (e1u[0] - e0u[0]).toDouble()
        val dy = (e1u[1] - e0u[1]).toDouble()
        val dz = (e1u[2] - e0u[2]).toDouble()
        val da = (e1u[3] - e0u[3]).toDouble()
        val lenSq = dx * dx + dy * dy + dz * dz + da * da
        val wSum = DoubleArray(cfg.weightCount)
        val wCnt = DoubleArray(cfg.weightCount)
        val ds = (1024 + cfg.footprintW / 2) / (cfg.footprintW - 1)
        val dt = (1024 + cfg.footprintH / 2) / (cfg.footprintH - 1)
        for (py in 0 until cfg.footprintH) {
            val gt = (dt * py * (cfg.gridH - 1) + 32) shr 6
            val jt = gt shr 4; val ft = gt and 0xF
            for (px in 0 until cfg.footprintW) {
                val gs = (ds * px * (cfg.gridW - 1) + 32) shr 6
                val js = gs shr 4; val fs = gs and 0xF
                val p = blockPixels[py * cfg.footprintW + px]
                val wv: Double = if (lenSq < 1e-6) 32.0 else {
                    // pr 用整数精确计算（与参考实现一致），再转 double 除法
                    val pr = ((r8(p) - e0u[0]) * (e1u[0] - e0u[0]) +
                        (g8(p) - e0u[1]) * (e1u[1] - e0u[1]) +
                        (b8(p) - e0u[2]) * (e1u[2] - e0u[2]) +
                        (a8(p) - e0u[3]) * (e1u[3] - e0u[3])).toDouble()
                    (pr / lenSq).coerceIn(0.0, 1.0) * 64.0
                }
                // 双线性系数（与解码端 infill 公式逐位一致）
                val w11 = (fs * ft + 8) shr 4
                val w10 = ft - w11
                val w01 = fs - w11
                val w00 = 16 - fs - ft + w11
                if (w00 > 0 && jt < cfg.gridH && js < cfg.gridW) {
                    val i = jt * cfg.gridW + js; wSum[i] += wv * w00; wCnt[i] += w00.toDouble()
                }
                if (w01 > 0 && jt < cfg.gridH && js + 1 < cfg.gridW) {
                    val i = jt * cfg.gridW + js + 1; wSum[i] += wv * w01; wCnt[i] += w01.toDouble()
                }
                if (w10 > 0 && jt + 1 < cfg.gridH && js < cfg.gridW) {
                    val i = (jt + 1) * cfg.gridW + js; wSum[i] += wv * w10; wCnt[i] += w10.toDouble()
                }
                if (w11 > 0 && jt + 1 < cfg.gridH && js + 1 < cfg.gridW) {
                    val i = (jt + 1) * cfg.gridW + js + 1; wSum[i] += wv * w11; wCnt[i] += w11.toDouble()
                }
            }
        }
        val weights = IntArray(cfg.weightCount)
        for (i in 0 until cfg.weightCount) {
            val w64 = if (wCnt[i] > 0.0) wSum[i] / wCnt[i] else 32.0
            weights[i] = quantTable[(w64 + 0.5).toInt().coerceIn(0, 64)]
        }

        // ---------- 4. 位打包 ----------
        val writer = BitWriter(128)
        // bits[10:0] block mode
        writer.write(cfg.blockMode, 11)
        // bits[12:11] part = 0（单分区）
        writer.write(0, 2)
        // bits[16:13] CEM = 12
        writer.write(CEM, 4)
        // bits[31:17] 端点 ISE 数据（v0..v7 = R0,R1,G0,G1,B0,B1,A0,A1）
        val epVals = intArrayOf(e0q[0], e1q[0], e0q[1], e1q[1], e0q[2], e1q[2], e0q[3], e1q[3])
        val epStream = epRangeIseBits(NUM_EP_VALUES, epRange)
        val epWriter = BitWriter(epStream)
        writeIseSequence(epVals, range.trits, range.quints, range.bits, epWriter)
        for (i in 0 until epStream) {
            if ((epWriter.data[i ushr 3].toInt() shr (i and 7)) and 1 == 1) {
                val pos = 17 + i
                writer.data[pos ushr 3] = (writer.data[pos ushr 3].toInt() or (1 shl (pos and 7))).toByte()
            }
        }
        // ---------- 权重 ISE：自块顶向下生长（stream bit i = block bit 127-i） ----------
        val wWriter = BitWriter(cfg.weightStreamBits)
        writeIseSequence(weights, cfg.weightTrits, cfg.weightQuints, cfg.weightBits, wWriter)
        for (i in 0 until cfg.weightStreamBits) {
            if ((wWriter.data[i ushr 3].toInt() shr (i and 7)) and 1 == 1) {
                val pos = 127 - i
                writer.data[pos ushr 3] = (writer.data[pos ushr 3].toInt() or (1 shl (pos and 7))).toByte()
            }
        }
        return writer.data
    }

    /** void-extent 恒色块（LDR UNORM16 = v*257） */
    internal fun encodeVoidExtent(argb: Int): ByteArray {
        val writer = BitWriter(128)
        // bits[11:0] = 11 D 1111111 00（bit11/bit10 保留必须 1，bit9=D=0 LDR，
        // bits[8:2]=1111111，bits[1:0]=00）
        val mode = 0x1FC or (0 shl 9) or (0b11 shl 10)
        writer.write(mode, 12)
        // bits[63:12] extent 坐标全 1（规范：全 1 时 void extent 被忽略，等效纯色块）
        for (i in 0 until 52) writer.write(1, 1)
        // 颜色 UNORM16：R bit[79:64], G bit[95:80], B bit[111:96], A bit[127:112]
        writer.write(u16(r8(argb)), 16)
        writer.write(u16(g8(argb)), 16)
        writer.write(u16(b8(argb)), 16)
        writer.write(u16(a8(argb)), 16)
        return writer.data
    }

    private fun u16(v: Int) = (v shl 8) or v

    private fun r8(p: Int) = (p ushr 16) and 0xFF
    private fun g8(p: Int) = (p ushr 8) and 0xFF
    private fun b8(p: Int) = p and 0xFF
    private fun a8(p: Int) = (p ushr 24) and 0xFF

    // ============================ 软件解码器（roundtrip 验证） ============================

    /**
     * 解码 ASTC 数据（本编码器生成的格式子集）为 0xAARRGGBB 像素。
     * 严格按解码器路径：block mode → 剩余位推断端点范围 → 端点 ISE → unquant
     * → CEM12 → 权重 ISE（自块顶向下）→ unquant → infill → 插值。
     */
    fun decode(data: ByteArray, width: Int, height: Int, blockFootprint: Int): IntArray {
        val cfg = configs[blockFootprint]
            ?: throw IllegalArgumentException("unsupported footprint $blockFootprint")
        val blocksW = (width + cfg.footprintW - 1) / cfg.footprintW
        val blocksH = (height + cfg.footprintH - 1) / cfg.footprintH
        val out = IntArray(width * height)

        for (by in 0 until blocksH) {
            for (bx in 0 until blocksW) {
                decodeBlock(data, (by * blocksW + bx) * 16, cfg, bx, by, width, height, out)
            }
        }
        return out
    }

    private fun decodeBlock(
        data: ByteArray, byteOffset: Int, cfg: BlockConfig,
        bx: Int, by: Int, width: Int, height: Int,
        out: IntArray
    ) {
        val baseBit = byteOffset * 8
        val reader = BitReader(data, baseBit)
        val mode = reader.read(11)

        // void extent？（此时 reader 位于 bit 11：编码器布局 mode(11)+保留(1)+extent(52)+RGBA(64)=128）
        if ((mode and 0x3) == 0 && ((mode shr 2) and 0x7F) == 0x7F) {
            // bit9（11 位 block mode 内）= HDR 标志；bit10 保留必须 1
            val isHdr = (mode shr 9) and 1
            check(isHdr == 0) { "HDR void extent 不支持" }
            reader.read(1)  // bit11：保留位（编码器写 1）
            reader.read(52) // bits[63:12]：extent 坐标（全 1 = 忽略）
            val r = reader.read(16); val g = reader.read(16)
            val b = reader.read(16); val a = reader.read(16)
            val color = (shrink16(a) shl 24) or (shrink16(r) shl 16) or (shrink16(g) shl 8) or shrink16(b)
            for (y in 0 until cfg.footprintH) {
                val sy = by * cfg.footprintH + y
                if (sy >= height) break
                for (x in 0 until cfg.footprintW) {
                    val sx = bx * cfg.footprintW + x
                    if (sx >= width) break
                    out[sy * width + sx] = color
                }
            }
            return
        }

        val part = reader.read(2)
        val cem = reader.read(4)

        require(mode == cfg.blockMode) { "block mode $mode 不在解码器支持范围" }
        require(part == 0) { "仅支持单分区" }
        require(cem == CEM) { "仅支持 CEM 12, got $cem" }

        // ---------- 端点范围推断（与解码器一致） ----------
        val remaining = 128 - CONFIG_BITS - cfg.weightStreamBits
        val epRange = selectEpRange(NUM_EP_VALUES, remaining)
        val range = EP_RANGES[epRange]
        val epUnquant = epUnquantTable(epRange)

        // ---------- 端点 ISE（bit 17 向上；部分组只读 k 个值的位） ----------
        val epReader = BitReader(data, baseBit + 17)
        val eVals = readIseSequence(NUM_EP_VALUES, range.trits, range.quints, range.bits, epReader)
        val v = IntArray(8) { epUnquant[eVals[it]] }

        // CEM 12 解码（规范 LDR Endpoint Mode 12）
        val s0 = v[0] + v[2] + v[4]
        val s1 = v[1] + v[3] + v[5]
        val e0: IntArray; val e1: IntArray
        if (s1 >= s0) {
            e0 = intArrayOf(v[0], v[2], v[4], v[6])
            e1 = intArrayOf(v[1], v[3], v[5], v[7])
        } else {
            e0 = blueContract(v[1], v[3], v[5], v[7])
            e1 = blueContract(v[0], v[2], v[4], v[6])
        }

        // ---------- 权重 ISE（stream bit i = block bit 127-i，自块顶向下） ----------
        val wStream = ByteArray((cfg.weightStreamBits + 7) / 8)
        for (i in 0 until cfg.weightStreamBits) {
            val p = 127 - i
            val bit = (data[byteOffset + (p ushr 3)].toInt() shr (p and 7)) and 1
            if (bit == 1) wStream[i ushr 3] = (wStream[i ushr 3].toInt() or (1 shl (i and 7))).toByte()
        }
        val wReader = BitReader(wStream)
        val storedWeights = readIseSequence(
            cfg.weightCount,
            cfg.weightTrits, cfg.weightQuints, cfg.weightBits,
            wReader
        )
        val gridWeights = IntArray(cfg.weightCount) { unquantWeight(storedWeights[it], cfg) }

        // ---------- infill（双线性，规范公式） ----------
        val ds = (1024 + cfg.footprintW / 2) / (cfg.footprintW - 1)
        val dt = (1024 + cfg.footprintH / 2) / (cfg.footprintH - 1)

        for (py in 0 until cfg.footprintH) {
            val sy = by * cfg.footprintH + py
            if (sy >= height) break
            val gt = (dt * py * (cfg.gridH - 1) + 32) shr 6
            val jt = gt shr 4; val ft = gt and 0xF
            for (px in 0 until cfg.footprintW) {
                val sx = bx * cfg.footprintW + px
                if (sx >= width) break
                val gs = (ds * px * (cfg.gridW - 1) + 32) shr 6
                val js = gs shr 4; val fs = gs and 0xF

                val v0 = js + jt * cfg.gridW
                val p00 = gridWeights[v0]
                val p01 = if (js + 1 < cfg.gridW) gridWeights[v0 + 1] else p00
                val p10 = if (jt + 1 < cfg.gridH) gridWeights[v0 + cfg.gridW] else p00
                val p11 = if (js + 1 < cfg.gridW && jt + 1 < cfg.gridH) gridWeights[v0 + cfg.gridW + 1]
                else if (jt + 1 < cfg.gridH) gridWeights[v0 + cfg.gridW] else if (js + 1 < cfg.gridW) gridWeights[v0 + 1] else p00

                val w11 = (fs * ft + 8) shr 4
                val w10 = ft - w11
                val w01 = fs - w11
                val w00 = 16 - fs - ft + w11
                val i = (p00 * w00 + p01 * w01 + p10 * w10 + p11 * w11 + 8) shr 4

                // ---------- 插值（LDR：位复制到 16 位再插值，取高 8 位） ----------
                val r = interp8(e0[0], e1[0], i)
                val g = interp8(e0[1], e1[1], i)
                val b = interp8(e0[2], e1[2], i)
                val a = interp8(e0[3], e1[3], i)
                out[sy * width + sx] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    /** LDR 插值：端点 8 位 → 位复制 16 位 → (C0*(64-i)+C1*i+32)/64 → 取高 8 位 */
    private fun interp8(c0: Int, c1: Int, i: Int): Int {
        val c0e = (c0 shl 8) or c0
        val c1e = (c1 shl 8) or c1
        val c = (c0e * (64 - i) + c1e * i + 32) shr 6
        return c shr 8
    }

    private fun blueContract(r: Int, g: Int, b: Int, a: Int): IntArray =
        intArrayOf((r + b) shr 1, (g + b) shr 1, b, a)

    private fun shrink16(v: Int): Int {
        // UNORM16 → 8 位（decode_unorm8 取高 8 位）
        return v shr 8
    }
}

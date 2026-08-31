package com.assetstudio.mobile.core.crypto

import com.assetstudio.mobile.core.bundle.LZ4

/*
 * AB管理器「加密资产文件（AB加密包）」加载器。
 *
 * 逆向来源（com.abglq，jadx 反编译，逻辑未被 VMP 保护）：
 *   - defpackage/c.java    魔数 / 64 字节 XOR 密钥 / 三次反转旋转 / u32→8 字节大端
 *   - defpackage/dn0.Z()   加密包识别 + blocksInfo 解密入口
 *   - defpackage/dn0.N()   LZ4 块解压
 *   - defpackage/dn0.G()   blocksInfo 解析 + 存储块重组
 *
 * 加密包格式（三个版本，仅 blocksInfo 清单被加密，数据块本体为普通 LZ4 压缩）：
 *   魔数            版本   blocksInfo 密文起始
 *   "UnityKHFS"      v0     魔长+43+12
 *   "UnityKHNFS"     v1     魔长+43+11
 *   "UnityKH1FS"     v2     魔长+43+11
 *   头部字段（相对魔数末尾）：+31 u32 密文长度 | +35 u32 明文长度 | +39 u32 flags
 *   flags: &63 压缩类型(0无/2 LZ4/3 LZ4HC)，&512 数据区 16 字节对齐
 *
 * blocksInfo 明文布局与标准 UnityFS 完全一致（hash16B + 块表 + 条目表），
 * 因此本类的策略是：解密（必要时解压）blocksInfo 后，用原始压缩数据块原样
 * 重建一个标准 UnityFS 容器，交给现有 BundleFile 解析——块解压、目录切分、
 * 资产分发全部复用原生链路。
 *
 * 三版解密算法（对密文字节序列操作，len = 密文长度）：
 *   v0: XOR(KeyA)
 *   v1: XOR(KeyB) 再 XOR(大端 8 字节的密文长度)
 *   v2: r = len%7
 *       整段旋转 c_d(buf, 0, len, (r+7)%len)
 *       XOR(r==0 或 len%3==0 或 len%5==0 ? KeyB : KeyA)
 *       XOR(大端 8 字节密文长度)
 *       若 (r+7)%len > 1:
 *           step = (r+1) % (r+7)%len
 *           对 pos = 0, step2, 2*step2, ...: c_d(buf, pos, step2, step)  // step2=(r+7)%len
 *           c_d(buf, 0, len, step)
 */
object KhBundleDecoder {

    /** c.a / c.b：64 字节 ASCII 密钥（XOR 循环使用；注意 $ 需转义避免字符串模板） */
    private val KEY_A =
        "X@85Pq!6v\$lCt7UYsihH3!cPb1P71bo4lX59FXqY!VO\$YiYsu!Keu3aVZwi5on5l"
            .toByteArray(Charsets.US_ASCII)
    private val KEY_B =
        "hAi5luE8FlyblDdCTQC9uxnj3rkNwd1swrKI7Mx1aDFEe2B5h#3X&s54%GuSeHf@"
            .toByteArray(Charsets.US_ASCII)

    /** 魔数 → 加密版本（c.c/c.d/c.e） */
    private val MAGICS = arrayOf(
        "UnityKHFS" to 0,
        "UnityKHNFS" to 1,
        "UnityKH1FS" to 2
    )

    /** 解码结果：重建的标准 UnityFS 字节流 + 展示用说明 */
    class KhResult(val unityFs: ByteArray, val note: String)

    /** 是否为 AB 加密包（魔数精确匹配，绝不误判标准文件） */
    fun detectVersion(data: ByteArray): Int {
        for ((magic, version) in MAGICS) {
            val m = magic.toByteArray(Charsets.US_ASCII)
            if (data.size >= m.size && m.indices.all { data[it] == m[it] }) {
                return version
            }
        }
        return -1
    }

    /**
     * 解码入口：解密 blocksInfo 并以原始数据块重建标准 UnityFS。
     * @return null 表示不是 AB 加密包（调用方走原逻辑）；
     *         魔数匹配但数据损坏时抛出异常（带明确原因）
     */
    fun decode(data: ByteArray): KhResult? {
        val version = detectVersion(data)
        if (version < 0) return null
        val magicLen = MAGICS[version].first.length
        val magicName = MAGICS[version].first

        if (data.size < magicLen + 43) {
            throw IllegalArgumentException("AB加密包头不完整（文件仅 ${data.size} 字节）")
        }
        // 头部字段：+31 密文长度 / +35 明文长度 / +39 flags
        val cipherLen = u32be(data, magicLen + 31)
        val plainLen = u32be(data, magicLen + 35)
        val flags = u32be(data, magicLen + 39)
        if (cipherLen <= 0 || cipherLen > data.size) {
            throw IllegalArgumentException("AB加密包 blocksInfo 长度无效: $cipherLen")
        }
        if (plainLen <= 0 || plainLen > MAX_BLOCKS_INFO) {
            throw IllegalArgumentException("AB加密包 blocksInfo 明文长度无效: $plainLen")
        }
        // 密文起始：v0 魔长+43+12，v1/v2 魔长+43+11（dn0.Z 的 i6）
        val infoStart = magicLen + 43 + (if (version == 0) 12 else 11)
        if (infoStart + cipherLen > data.size) {
            throw IllegalArgumentException("AB加密包数据不完整（blocksInfo 越界）")
        }
        val encrypted = data.copyOfRange(infoStart, infoStart + cipherLen)

        // ★ 核心解密（v0/v1/v2 三版算法）
        val decrypted = decryptBlocksInfo(encrypted, version)
        // blocksInfo 可能压缩（flags&63：0 无 / 2 LZ4 / 3 LZ4HC，与标准 UnityFS 同义）
        val blocksInfo = decompress(decrypted, plainLen, flags and 0x3f)

        // 数据区起始：密文之后，flags&512 时 16 字节对齐
        var dataStart = infoStart + cipherLen
        if (flags and 0x200 != 0) {
            dataStart = (dataStart + 15) and 15.inv()
        }
        if (dataStart > data.size) {
            throw IllegalArgumentException("AB加密包数据区起始偏移越界: $dataStart")
        }

        val note = "AB管理器加密包 v$version（$magicName）已自动解密"
        return KhResult(buildUnityFs(blocksInfo, data, dataStart), note)
    }

    /** blocksInfo 明文长度上限（防字段损坏导致的异常分配） */
    private const val MAX_BLOCKS_INFO = 16 shl 20

    /**
     * 以解密后的 blocksInfo + 原始压缩数据块重建标准 UnityFS 容器。
     * 合成头固定 version=6 / flags=0（blocksInfo 不压缩、无对齐填充），
     * 数据块字节原样拷贝——块内压缩方式由 blocksInfo 的块表自带，
     * 与标准 UnityFS 语义一致，BundleFile 会按块表自行解压。
     */
    private fun buildUnityFs(blocksInfo: ByteArray, source: ByteArray, dataStart: Int): ByteArray {
        val headerLen = 8 + 4 + 6 + 6 + 8 + 4 + 4 + 4 // 见下方逐字段
        val blockBytes = source.size - dataStart
        val total = headerLen + blocksInfo.size + blockBytes
        if (total < 0) throw IllegalArgumentException("AB加密包重建长度溢出")
        val out = ByteArray(total)
        var p = 0

        fun u8(v: Int) { out[p++] = v.toByte() }
        fun u32(v: Int) {
            out[p++] = (v ushr 24).toByte(); out[p++] = (v ushr 16).toByte()
            out[p++] = (v ushr 8).toByte(); out[p++] = v.toByte()
        }
        fun cstr(s: String) { for (b in s.toByteArray(Charsets.US_ASCII)) u8(b.toInt()); u8(0) }

        // signature "UnityFS\0"
        cstr("UnityFS")
        // format version = 6（<7 无头对齐填充，解析最简）
        u32(6)
        // unityVersion / unityRevision：仅对极老（<Unknown_7）的序列化文件作版本提示
        cstr("5.x.x")
        cstr("5.x.x")
        // fileSize（完整合成文件长度）
        var v = total.toLong()
        for (i in 7 downTo 0) { u8((v ushr (i * 8)).toInt()); }
        // compressedBlocksInfoSize / uncompressedBlocksInfoSize（存明文，两者相等）
        u32(blocksInfo.size)
        u32(blocksInfo.size)
        // flags = 0：blocksInfo 不压缩、不置于文件尾、无起始填充
        u32(0)

        // blocksInfo 明文
        System.arraycopy(blocksInfo, 0, out, p, blocksInfo.size)
        p += blocksInfo.size
        // 原始数据块（保持原压缩状态）
        if (blockBytes > 0) {
            System.arraycopy(source, dataStart, out, p, blockBytes)
        }
        return out
    }

    // ============================ 三版解密/加密 ============================

    /** dn0.Z 的解密分支（逐句移植自验证过的参考实现） */
    fun decryptBlocksInfo(enc: ByteArray, version: Int): ByteArray {
        val d = enc.copyOf()
        val n = d.size
        when (version) {
            0 -> xorInPlace(d, KEY_A)
            1 -> {
                xorInPlace(d, KEY_B)
                xorInPlace(d, lenBe8(n))
            }
            2 -> {
                val r = n % 7
                val step2 = (r + 7) % n
                rotate(d, 0, n, step2)
                xorInPlace(d, if (n % 3 == 0 || n % 5 == 0 || r == 0) KEY_B else KEY_A)
                xorInPlace(d, lenBe8(n))
                if (step2 > 1) {
                    val step = (r + 1) % step2
                    var pos = 0
                    while (pos < n) {
                        rotate(d, pos, step2, step)
                        pos += step2
                    }
                    rotate(d, 0, n, step)
                }
            }
            else -> throw IllegalArgumentException("未知加密版本: $version")
        }
        return d
    }

    /** decryptBlocksInfo 的逆变换（往返测试用；亦为将来回写加密预留） */
    fun encryptBlocksInfo(plain: ByteArray, version: Int): ByteArray {
        val d = plain.copyOf()
        val n = d.size
        when (version) {
            0 -> xorInPlace(d, KEY_A)
            1 -> {
                xorInPlace(d, lenBe8(n))
                xorInPlace(d, KEY_B)
            }
            2 -> {
                val r = n % 7
                val step2 = (r + 7) % n
                if (step2 > 1) {
                    val step = (r + 1) % step2
                    invRotate(d, 0, n, step)
                    var pos = 0
                    while (pos < n) {
                        invRotate(d, pos, step2, step)
                        pos += step2
                    }
                }
                xorInPlace(d, lenBe8(n))
                xorInPlace(d, if (n % 3 == 0 || n % 5 == 0 || r == 0) KEY_B else KEY_A)
                invRotate(d, 0, n, step2)
            }
            else -> throw IllegalArgumentException("未知加密版本: $version")
        }
        return d
    }

    // ============================ 字节变换原语（c.java） ============================

    /** c.e()：循环密钥异或（原地） */
    private fun xorInPlace(data: ByteArray, key: ByteArray) {
        if (key.isEmpty()) return
        for (i in data.indices) {
            data[i] = (data[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
    }

    /** c.a()：u32 → 8 字节大端（仅低 32 位有效） */
    private fun lenBe8(n: Int): ByteArray = ByteArray(8) { i ->
        ((n.toLong() and 0xFFFFFFFFL) ushr ((7 - i) * 8)).toByte()
    }

    /**
     * c.d()（z=false 路径）：三次反转实现段内循环右移。
     * 边界钳制与反编译源码一致：start/end 超出数组时钳到末位。
     */
    private fun rotate(a: ByteArray, start: Int, segLen: Int, rot: Int) {
        val last = a.size - 1
        var i = start
        if (i > last) i = last
        if (i < 0) i = 0
        var end = (i - 1) + segLen
        if (end > last) end = last
        val seg = end - i + 1
        if (seg < 2) return
        val r = ((rot % seg) + seg) % seg
        if (r == 0) return
        val t = (end - r).coerceIn(i, end)
        val upper = minOf(t + 1, last)
        rev(a, i, t)
        rev(a, upper, end)
        rev(a, i, end)
    }

    /** rotate 的逆变换：等价于反向旋转 */
    private fun invRotate(a: ByteArray, start: Int, segLen: Int, rot: Int) {
        val seg = segSize(a, start, segLen)
        if (seg < 1) return
        rotate(a, start, segLen, (seg - rot % seg) % seg)
    }

    /** rotate 实际作用到的段长（含钳制） */
    private fun segSize(a: ByteArray, start: Int, segLen: Int): Int {
        val last = a.size - 1
        var i = start
        if (i > last) i = last
        if (i < 0) i = 0
        var end = (i - 1) + segLen
        if (end > last) end = last
        return end - i + 1
    }

    /** c.c()：区间反转 [i, j] */
    private fun rev(a: ByteArray, i: Int, j: Int) {
        var x = i
        var y = j
        while (y > x) {
            val b = a[x]
            a[x] = a[y]
            a[y] = b
            x++
            y--
        }
    }

    // ============================ 辅助 ============================

    /** dn0.N()：blocksInfo 解压（0 无 / 2 LZ4 / 3 LZ4HC） */
    private fun decompress(src: ByteArray, uncompressedSize: Int, compressionType: Int): ByteArray {
        return when (compressionType) {
            0 -> src
            2, 3 -> {
                if (uncompressedSize <= 0 || uncompressedSize > MAX_BLOCKS_INFO) {
                    throw IllegalArgumentException("blocksInfo 明文长度无效: $uncompressedSize")
                }
                val out = ByteArray(uncompressedSize)
                val n = LZ4.decode(src, 0, src.size, out, 0, uncompressedSize)
                if (n != uncompressedSize) {
                    throw IllegalArgumentException(
                        "blocksInfo LZ4 解压不完整: $n / $uncompressedSize"
                    )
                }
                out
            }
            else -> throw IllegalArgumentException("不支持的压缩方式: $compressionType")
        }
    }

    /** 大端 u32 读取（bb0.s） */
    private fun u32be(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
}

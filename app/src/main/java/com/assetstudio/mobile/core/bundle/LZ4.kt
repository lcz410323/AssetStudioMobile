package com.assetstudio.mobile.core.bundle

/**
 * 纯 Kotlin 实现的 LZ4 block 格式编解码器。
 *
 * 在 C# AssetStudio 中由 K4os.Compression.LZ4.LZ4Codec 提供（BundleFile.cs 使用），
 * Android 端不引入第三方库，这里按 LZ4 block 格式规范重新实现：
 *
 * 序列（sequence）结构：
 *   token(1B) [字面量长度扩展] [字面量数据] [偏移(2B, LE)] [匹配长度扩展]
 *   - token 高 4 位：字面量长度（15 表示有扩展字节，扩展字节为 255 时继续）
 *   - token 低 4 位：匹配长度 - 4（15 表示有扩展字节）
 *   - 最后一个序列只包含字面量（不带匹配部分）
 *   - 偏移为小端 16 位，且必须指向当前已解压输出之内
 */
object LZ4 {

    private const val MIN_MATCH = 4
    private const val MFLIMIT = 12          // 匹配搜索至少距离结尾 12 字节（与参考实现一致）
    private const val LAST_LITERALS = 5     // 末尾至少 5 个字面量字节
    private const val HASH_LOG = 16
    private const val HASH_SIZE = 1 shl HASH_LOG
    private const val MAX_DISTANCE = 0xFFFF // 偏移用 16 位表示

    /**
     * LZ4 block 解压。
     * @return 写入 dst 的字节数，格式错误（越界/非法偏移/长度不符）返回 -1
     */
    fun decode(src: ByteArray, srcPos: Int, srcLen: Int, dst: ByteArray, dstPos: Int, dstLen: Int): Int {
        if (srcPos < 0 || srcLen < 0 || srcPos + srcLen > src.size) return -1
        if (dstPos < 0 || dstLen < 0 || dstPos + dstLen > dst.size) return -1

        var s = srcPos
        val srcEnd = srcPos + srcLen
        var d = dstPos
        val dstEnd = dstPos + dstLen

        while (s < srcEnd) {
            // token
            val token = src[s++].toInt() and 0xFF

            // 字面量长度
            var litLen = token ushr 4
            if (litLen == 15) {
                while (true) {
                    if (s >= srcEnd) return -1
                    val b = src[s++].toInt() and 0xFF
                    litLen += b
                    if (b != 255) break
                }
            }
            if (s + litLen > srcEnd || d + litLen > dstEnd) return -1
            if (litLen > 0) {
                System.arraycopy(src, s, dst, d, litLen)
                s += litLen
                d += litLen
            }

            // 最后一个序列只有字面量
            if (s >= srcEnd) break

            // 匹配偏移（小端 16 位）
            if (s + 2 > srcEnd) return -1
            val offset = (src[s++].toInt() and 0xFF) or ((src[s++].toInt() and 0xFF) shl 8)
            if (offset == 0 || offset > d - dstPos) return -1

            // 匹配长度
            var matchLen = token and 0x0F
            if (matchLen == 15) {
                while (true) {
                    if (s >= srcEnd) return -1
                    val b = src[s++].toInt() and 0xFF
                    matchLen += b
                    if (b != 255) break
                }
            }
            matchLen += MIN_MATCH
            if (d + matchLen > dstEnd) return -1

            // 带重叠的拷贝（必须逐字节，不能重叠 System.arraycopy）
            var m = d - offset
            var count = matchLen
            while (count-- > 0) {
                dst[d++] = dst[m++]
            }
        }
        return d - dstPos
    }

    /** 便捷重载：解压整个 src 到 dst */
    fun decode(src: ByteArray, dst: ByteArray): Int =
        decode(src, 0, src.size, dst, 0, dst.size)

    /**
     * LZ4 block 压缩（贪心 + 哈希表匹配，输出保证可被 decode 正确还原，不追求压缩率最优）。
     */
    fun encode(src: ByteArray, srcPos: Int, srcLen: Int): ByteArray {
        if (srcPos < 0 || srcLen < 0 || srcPos + srcLen > src.size) {
            throw IllegalArgumentException("encode 参数越界: srcPos=$srcPos, srcLen=$srcLen, size=${src.size}")
        }
        val srcEnd = srcPos + srcLen
        val mflimit = srcEnd - MFLIMIT
        val out = Buffer(if (srcLen < 64) 64 else srcLen / 2)
        val hashTable = IntArray(HASH_SIZE) { -1 } // 存放已插入位置（绝对下标）

        var anchor = srcPos
        var ip = srcPos

        while (ip < mflimit) {
            val h = hash(src, ip)
            val ref = hashTable[h]
            hashTable[h] = ip
            if (ref >= srcPos && ip - ref <= MAX_DISTANCE && read32(src, ref) == read32(src, ip)) {
                // 扩展匹配（不超过 LAST_LITERALS 保护边界，保证末尾字面量）
                val matchLimit = srcEnd - LAST_LITERALS
                var len = MIN_MATCH
                while (ip + len < matchLimit && src[ref + len] == src[ip + len]) {
                    len++
                }
                val litLen = ip - anchor
                val tokenPos = out.size
                out.writeByte(0) // token 占位
                writeLengthExtension(out, litLen)
                out.writeBytes(src, anchor, litLen)
                val offset = ip - ref
                out.writeByte(offset and 0xFF)
                out.writeByte((offset ushr 8) and 0xFF)
                val ml = len - MIN_MATCH
                writeLengthExtension(out, ml)
                out.setByte(tokenPos, (minOf(litLen, 15) shl 4) or minOf(ml, 15))

                anchor = ip + len
                ip = anchor
            } else {
                ip++
            }
        }

        // 末尾字面量
        val litLen = srcEnd - anchor
        val tokenPos = out.size
        out.writeByte(0)
        writeLengthExtension(out, litLen)
        out.writeBytes(src, anchor, litLen)
        out.setByte(tokenPos, minOf(litLen, 15) shl 4)

        return out.toByteArray()
    }

    /** 便捷重载：压缩整个 src */
    fun encode(src: ByteArray): ByteArray = encode(src, 0, src.size)

    /** 长度扩展字节：len >= 15 时先写 0xFF 的序列再写余数（token 中的 15 表示有扩展） */
    private fun writeLengthExtension(out: Buffer, length: Int) {
        if (length < 15) return
        var remain = length - 15
        while (remain >= 255) {
            out.writeByte(255)
            remain -= 255
        }
        out.writeByte(remain)
    }

    private fun read32(src: ByteArray, p: Int): Int =
        (src[p].toInt() and 0xFF) or
            ((src[p + 1].toInt() and 0xFF) shl 8) or
            ((src[p + 2].toInt() and 0xFF) shl 16) or
            ((src[p + 3].toInt() and 0xFF) shl 24)

    /** 参考 LZ4 的 hash：32 位乘法散列取高位 */
    private fun hash(src: ByteArray, p: Int): Int =
        (read32(src, p) * -1640531535) ushr (32 - HASH_LOG)

    /** 可增长字节数组（支持回填 token） */
    private class Buffer(initialCapacity: Int) {
        var data: ByteArray = ByteArray(initialCapacity)
            private set
        var size: Int = 0
            private set

        fun ensure(extra: Int) {
            if (size + extra > data.size) {
                data = data.copyOf(maxOf(size + extra, data.size * 2))
            }
        }

        fun writeByte(b: Int) {
            ensure(1)
            data[size++] = b.toByte()
        }

        fun writeBytes(src: ByteArray, offset: Int, count: Int) {
            if (count <= 0) return
            ensure(count)
            System.arraycopy(src, offset, data, size, count)
            size += count
        }

        fun setByte(pos: Int, b: Int) {
            data[pos] = b.toByte()
        }

        fun toByteArray(): ByteArray = data.copyOf(size)
    }
}

package com.assetstudio.mobile

import com.assetstudio.mobile.core.bundle.BundleFile
import com.assetstudio.mobile.core.bundle.LZ4
import com.assetstudio.mobile.core.crypto.KhBundleDecoder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * AB管理器加密包（UnityKH*FS）加载测试：
 * 1. 三版解密/加密往返（覆盖 v2 旋转的全部分支：r=0..6、KeyA/KeyB 选择）
 * 2. 完整链路：构造 KH 包 → decode 重建标准 UnityFS → BundleFile 解析出资产
 * 3. blocksInfo LZ4 压缩 / 数据块 LZ4 压缩 / 数据区 16 字节对齐
 * 4. 魔数识别不误判标准文件；损坏数据给出明确异常
 */

/** 构造 blocksInfo 明文（与标准 UnityFS 布局一致） */
private fun buildBlocksInfo(
    blocks: List<Triple<Int, Int, Int>>,           // (解压长, 存储长, flags)
    entries: List<Triple<Long, Long, String>>       // (offset, size, name)
): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    fun u16(v: Int) { out.write(v ushr 8); out.write(v) }
    fun u32(v: Int) { out.write(v ushr 24); out.write(v ushr 16); out.write(v ushr 8); out.write(v) }
    fun i64(v: Long) { for (i in 7 downTo 0) out.write((v ushr (i * 8)).toInt()) }
    repeat(16) { out.write(0xAB) }                  // 哈希占位（解析时忽略）
    u32(blocks.size)
    for ((u, c, f) in blocks) { u32(u); u32(c); u16(f) }
    u32(entries.size)
    for ((o, s, name) in entries) {
        i64(o); i64(s); u32(0)
        for (b in name.toByteArray(Charsets.UTF_8)) out.write(b.toInt())
        out.write(0)
    }
    return out.toByteArray()
}

/**
 * 构造 AB 加密包（加密方向 = 解密的逆，与参考实现一致）。
 * @param version 0/1/2
 * @param payload 数据区原始字节（未压缩）
 * @param compressInfo blocksInfo 是否 LZ4 压缩后加密
 * @param compressBlock 数据块是否 LZ4 压缩存储
 * @param align 置位 flags&512（数据区 16 字节对齐）
 */
private fun khBundle(
    version: Int,
    payload: ByteArray,
    compressInfo: Boolean = false,
    compressBlock: Boolean = false,
    align: Boolean = false
): ByteArray {
    val magic = arrayOf("UnityKHFS", "UnityKHNFS", "UnityKH1FS")[version].toByteArray(Charsets.US_ASCII)
    val ml = magic.size

    val block = if (compressBlock) LZ4.encode(payload) else payload
    val blockFlags = if (compressBlock) 2 else 0
    val infoPlain = buildBlocksInfo(
        listOf(Triple(payload.size, block.size, blockFlags)),
        listOf(Triple(0L, payload.size.toLong(), "assets/demo.assets"))
    )
    val infoStored = if (compressInfo) LZ4.encode(infoPlain) else infoPlain
    val enc = KhBundleDecoder.encryptBlocksInfo(infoStored, version)

    val out = java.io.ByteArrayOutputStream()
    fun u32(v: Int) { out.write(v ushr 24); out.write(v ushr 16); out.write(v ushr 8); out.write(v) }
    out.write(magic)
    // 保留区：到偏移 ml+31（含魔数在内共 ml+31 字节）
    repeat(ml + 31 - ml) { out.write(0x5A) } // 偏移 [ml, ml+31)，共 31 字节
    // 字段区：+31 密文长 / +35 明文长 / +39 flags
    u32(enc.size)
    u32(infoPlain.size)
    var flags = (if (compressInfo) 2 else 0) or (if (align) 0x200 else 0)
    u32(flags)
    // 填充：v0 12 字节，v1/v2 11 字节（至密文起始 ml+43+12/11）
    repeat(if (version == 0) 12 else 11) { out.write(0) }
    out.write(enc)
    if (align) {
        while (out.size() % 16 != 0) out.write(0)
    }
    out.write(block)
    return out.toByteArray()
}

class KhBundleDecoderTest {

    /** 可辨识的伪随机负载（非全零，保证压缩路径有实际数据） */
    private fun demoPayload(size: Int): ByteArray = ByteArray(size) { ((it * 31 + 7) % 251).toByte() }

    // ---------- 解密/加密往返（覆盖 v2 全部分支） ----------

    @Test
    fun `三版解密加密往返覆盖 v2 旋转与密钥选择分支`() {
        // 用不同长度的 blocksInfo 触发：n%7 = 0..6、n%3==0、n%5==0、两者皆非、
        // 以及不同 (r+7)%n / (r+1)%step2 组合
        for (version in 0..2) {
            for (pad in 0 until 14) {
                val plain = buildBlocksInfo(
                    listOf(Triple(100, 100, 0)),
                    listOf(Triple(0L, 100L, "assets/${"x".repeat(pad)}.assets"))
                )
                val enc = KhBundleDecoder.encryptBlocksInfo(plain, version)
                assertEquals(plain.size, enc.size)
                val dec = KhBundleDecoder.decryptBlocksInfo(enc, version)
                assertContentEquals(plain, dec, "v$version pad=$pad 往返失败")
            }
        }
    }

    @Test
    fun `v2 长明文多块表往返`() {
        // 更长的 blocksInfo（多块多条目）覆盖分段旋转的尾段钳制路径
        val plain = buildBlocksInfo(
            (1..8).map { Triple(4096, 3000 + it, if (it % 2 == 0) 2 else 0) },
            (1..5).map { Triple((it * 1000).toLong(), 999L, "dir/file$it.assets") }
        )
        val enc = KhBundleDecoder.encryptBlocksInfo(plain, 2)
        assertContentEquals(plain, KhBundleDecoder.decryptBlocksInfo(enc, 2))
    }

    // ---------- 魔数识别 ----------

    @Test
    fun `魔数识别`() {
        assertEquals(0, KhBundleDecoder.detectVersion("UnityKHFS".toByteArray()))
        assertEquals(1, KhBundleDecoder.detectVersion("UnityKHNFS".toByteArray()))
        assertEquals(2, KhBundleDecoder.detectVersion("UnityKH1FS".toByteArray()))
        // 标准文件与随机数据不误判
        assertEquals(-1, KhBundleDecoder.detectVersion("UnityFS\u0000...".toByteArray()))
        assertEquals(-1, KhBundleDecoder.detectVersion(ByteArray(1024) { it.toByte() }))
        assertEquals(-1, KhBundleDecoder.detectVersion(ByteArray(4)))
        // decode 对非 KH 文件返回 null
        assertNull(KhBundleDecoder.decode("UnityFS\u0000abcdef".toByteArray()))
    }

    // ---------- 完整链路：decode → BundleFile 解析 ----------

    @Test
    fun `v0 全不压缩端到端`() = endToEnd(0)
    @Test
    fun `v1 全不压缩端到端`() = endToEnd(1)
    @Test
    fun `v2 全不压缩端到端`() = endToEnd(2)

    @Test
    fun `blocksInfo 与数据块均 LZ4 压缩端到端`() = endToEnd(2, compressInfo = true, compressBlock = true)

    @Test
    fun `数据区 16 字节对齐端到端`() = endToEnd(1, align = true, compressBlock = true)

    private fun endToEnd(
        version: Int,
        compressInfo: Boolean = false,
        compressBlock: Boolean = false,
        align: Boolean = false
    ) {
        val payload = demoPayload(4096)
        val kh = khBundle(version, payload, compressInfo, compressBlock, align)
        val result = KhBundleDecoder.decode(kh)
        assertNotNull(result, "v$version 应识别为 AB 加密包")
        // 重建的 UnityFS 交给原生 BundleFile 解析
        val bundle = BundleFile(result.unityFs, "demo.kh")
        assertEquals("UnityFS", bundle.m_Header.signature)
        assertEquals(1, bundle.fileList.size)
        val file = bundle.fileList[0]
        assertEquals("assets/demo.assets", file.path)
        assertEquals("demo.assets", file.fileName)
        assertContentEquals(payload, file.data, "v$version 端到端资产字节不一致")
    }

    // ---------- 异常路径 ----------

    @Test
    fun `损坏的密文长度给出明确异常`() {
        val kh = khBundle(0, demoPayload(64))
        // 篡改 +31 处的密文长度字段
        kh[9 + 31] = 0x7F
        kh[9 + 32] = 0xFF.toByte()
        val e = assertFailsWith<IllegalArgumentException> { KhBundleDecoder.decode(kh) }
        assertTrue(e.message!!.contains("长度无效"))
    }

    @Test
    fun `文件过短给出明确异常`() {
        val e = assertFailsWith<IllegalArgumentException> {
            KhBundleDecoder.decode("UnityKHFS".toByteArray())
        }
        assertTrue(e.message!!.contains("不完整"))
    }
}

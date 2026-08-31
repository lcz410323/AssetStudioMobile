package com.assetstudio.mobile

import com.assetstudio.mobile.core.bundle.BundleFile
import com.assetstudio.mobile.core.bundle.LZ4
import com.assetstudio.mobile.replace.BundleRepacker
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * 重打包回归测试 —— 复现"另存后重新打开预览失败"的场景：
 * 1. 构造标准 UnityFS → BundleFile 解析 → 修改条目数据（模拟贴图替换）→ repack 另存
 * 2. 对另存输出再次 BundleFile 解析（模拟用户重新打开文件预览）
 * 3. 断言条目 path / size / data 与替换后完全一致
 *
 * 覆盖：单条目 / 多条目（CAB + .resS）offset 累计 / LZ4 压缩块 /
 *       源包 blocksInfo LZ4 压缩 / UnityWeb v6 签名保持。
 */

/** 构造 blocksInfo 明文（标准 UnityFS 布局：offset, size, flags, path） */
private fun buildBlocksInfo(
    blocks: List<Triple<Int, Int, Int>>,          // (解压长, 存储长, flags)
    entries: List<Triple<Long, Long, String>>      // (offset, size, name)
): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    fun u16(v: Int) { out.write(v ushr 8); out.write(v) }
    fun u32(v: Int) { out.write(v ushr 24); out.write(v ushr 16); out.write(v ushr 8); out.write(v) }
    fun i64(v: Long) { for (i in 7 downTo 0) out.write((v ushr (i * 8)).toInt()) }
    repeat(16) { out.write(0xAB) }                 // 哈希占位（解析时忽略）
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

/** 构造标准 UnityFS bundle（blocksInfo 可选 LZ4 压缩，数据块可选压缩） */
private fun buildUnityFs(
    entries: List<Pair<String, ByteArray>>,
    compressInfo: Boolean = false,
    compressBlocks: Boolean = false,
    signature: String = "UnityFS",
    version: Long = 6L
): ByteArray {
    // 数据区：所有条目顺序拼接
    val data = java.io.ByteArrayOutputStream()
    val entryTable = ArrayList<Triple<Long, Long, String>>()
    var off = 0L
    for ((name, bytes) in entries) {
        entryTable.add(Triple(off, bytes.size.toLong(), name))
        data.write(bytes)
        off += bytes.size
    }
    val payload = data.toByteArray()

    val blockStored = if (compressBlocks) LZ4.encode(payload) else payload
    val blockFlags = if (compressBlocks) 2 else 0

    val infoPlain = buildBlocksInfo(
        listOf(Triple(payload.size, blockStored.size, blockFlags)),
        entryTable
    )
    val infoStored = if (compressInfo) LZ4.encode(infoPlain) else infoPlain
    val infoFlags = if (compressInfo) 2 else 0

    val out = java.io.ByteArrayOutputStream()
    fun u32(v: Int) { out.write(v ushr 24); out.write(v ushr 16); out.write(v ushr 8); out.write(v) }
    fun i64(v: Long) { for (i in 7 downTo 0) out.write((v ushr (i * 8)).toInt()) }

    out.write(signature.toByteArray(Charsets.US_ASCII)); out.write(0)
    u32(version.toInt())
    out.write("5.x.x".toByteArray(Charsets.US_ASCII)); out.write(0)
    out.write("2019.4.1f1".toByteArray(Charsets.US_ASCII)); out.write(0)
    val sizePos = out.size()
    i64(0)                       // size 占位
    u32(infoStored.size)
    u32(infoPlain.size)
    u32(infoFlags or 0x40)       // BlocksAndDirectoryInfoCombined
    if (signature != "UnityFS") out.write(0)  // 解析端非 UnityFS 签名多读 1 字节
    out.write(infoStored)
    out.write(blockStored)

    val bytes = out.toByteArray()
    i64At(bytes, sizePos, bytes.size.toLong())
    return bytes
}

private fun i64At(buf: ByteArray, pos: Int, v: Long) {
    for (i in 0 until 8) buf[pos + i] = ((v ushr (8 * (7 - i))) and 0xFF).toByte()
}

/** 可辨识伪随机负载（不可压缩，repack 端会退化为不压缩块） */
private fun noise(size: Int): ByteArray = ByteArray(size) { ((it * 31 + 7) % 251).toByte() }

class BundleRepackerTest {

    // ---------- 核心场景：另存后重新打开，条目元数据必须可正确解析 ----------

    @Test
    fun `单条目替换后重新解析条目完整`() {
        val original = noise(5000)
        val bundle = BundleFile(
            buildUnityFs(listOf("CAB-8272688474fc22cf" to original)), "test.bundle"
        )
        assertEquals(1, bundle.fileList.size)

        // 模拟贴图替换：数据变化（尺寸不同）
        val replaced = noise(6000)
        bundle.fileList[0].data = replaced

        val saved = BundleRepacker.repack(bundle)

        // 模拟用户重新打开另存文件
        val reopened = BundleFile(saved, "saved.bundle")
        assertEquals(1, reopened.fileList.size)
        val entry = reopened.fileList[0]
        assertEquals("CAB-8272688474fc22cf", entry.path)
        assertEquals(replaced.size.toLong(), entry.data.size.toLong())
        assertContentEquals(replaced, entry.data)
    }

    @Test
    fun `多条目替换后重新解析 offset 与数据均正确`() {
        val cab = noise(3000)
        val resS = noise(4000)
        val bundle = BundleFile(
            buildUnityFs(listOf("CAB-abc123" to cab, "CAB-abc123.resS" to resS)), "test.bundle"
        )
        assertEquals(2, bundle.fileList.size)

        // 只替换第一个条目（贴图在 serialized file 内联数据的典型情况）
        val newCab = noise(3500)
        bundle.fileList[0].data = newCab

        val reopened = BundleFile(BundleRepacker.repack(bundle), "saved.bundle")
        assertEquals(2, reopened.fileList.size)
        assertEquals("CAB-abc123", reopened.fileList[0].path)
        assertEquals("CAB-abc123.resS", reopened.fileList[1].path)
        assertContentEquals(newCab, reopened.fileList[0].data)
        assertContentEquals(resS, reopened.fileList[1].data)
    }

    // ---------- 源包形态覆盖 ----------

    @Test
    fun `源包 blocksInfo 与数据块均 LZ4 压缩时替换后仍可解析`() {
        val payload = ByteArray(8000) { (it / 64).toByte() }  // 可压缩模式
        val bundle = BundleFile(
            buildUnityFs(
                listOf("CAB-compressed" to payload),
                compressInfo = true,
                compressBlocks = true
            ), "test.bundle"
        )
        assertContentEquals(payload, bundle.fileList[0].data)  // 源解析正确

        val replaced = noise(7000)
        bundle.fileList[0].data = replaced

        val reopened = BundleFile(BundleRepacker.repack(bundle), "saved.bundle")
        assertEquals("CAB-compressed", reopened.fileList[0].path)
        assertContentEquals(replaced, reopened.fileList[0].data)
    }

    @Test
    fun `UnityWeb v6 签名替换后保持可解析`() {
        val bundle = BundleFile(
            buildUnityFs(
                listOf("CAB-webv6" to noise(2000)),
                signature = "UnityWeb", version = 6L
            ), "test.unity3d"
        )
        val replaced = noise(2500)
        bundle.fileList[0].data = replaced

        val saved = BundleRepacker.repack(bundle)
        assertEquals("UnityWeb", String(saved.copyOfRange(0, 8), Charsets.US_ASCII))

        val reopened = BundleFile(saved, "saved.unity3d")
        assertEquals("CAB-webv6", reopened.fileList[0].path)
        assertContentEquals(replaced, reopened.fileList[0].data)
    }

    @Test
    fun `大尺寸条目替换后 size 字段不溢出错位`() {
        // 超过单块 128KB（repack 会切多块），验证块表与目录表均正确
        val big = noise(300 * 1024)
        val bundle = BundleFile(
            buildUnityFs(listOf("CAB-big" to big)), "test.bundle"
        )
        assertContentEquals(big, bundle.fileList[0].data)

        val reopened = BundleFile(BundleRepacker.repack(bundle), "saved.bundle")
        assertEquals(big.size, reopened.fileList[0].data.size)
        assertContentEquals(big, reopened.fileList[0].data)
    }

    @Test
    fun `canRepack 按签名与版本判断`() {
        val fs = BundleFile(buildUnityFs(listOf("a" to noise(16))), "t")
        val web6 = BundleFile(
            buildUnityFs(listOf("a" to noise(16)), signature = "UnityWeb", version = 6L), "t"
        )
        assertTrue(BundleRepacker.canRepack(fs))
        assertTrue(BundleRepacker.canRepack(web6))
    }
}

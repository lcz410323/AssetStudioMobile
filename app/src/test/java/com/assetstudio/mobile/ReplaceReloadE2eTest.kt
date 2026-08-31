package com.assetstudio.mobile

import com.assetstudio.mobile.core.bundle.BundleFile
import com.assetstudio.mobile.core.bundle.LZ4
import com.assetstudio.mobile.core.manager.AssetsManager
import com.assetstudio.mobile.core.serialized.SerializedFile
import com.assetstudio.mobile.replace.BundleRepacker
import com.assetstudio.mobile.replace.SerializedFileRewriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * 端到端复现："替换贴图 → 另存 → 重新打开（.bin 后缀）→ 加载不出资产"
 *
 * 完整链路：
 *   构造真实 SerializedFile(v17, 单对象) → 包进 UnityFS bundle
 *   → AssetsManager 加载（原文件） → SerializedFileRewriter 替换对象字节（尺寸变化）
 *   → BundleRepacker.repack 另存 → 新 AssetsManager 以 .bin 后缀重新加载
 *   → 断言资产文件与对象表全部可解析、偏移/大小与替换后一致
 *
 * 该测试不依赖 Texture2D 编码，仅验证容器/序列化文件两级重写与重新解析的正确性
 * （AssetReplacer 的替换编排在这两级之上，逻辑等价）。
 */

/** 端到端测试共享资产构造器 */
object TestAssets {

/**
 * 构造最小合法 SerializedFile（version=17 小端元数据，单类型单对象）。
 * 布局与 SerializedFile 解析器逐字段对应：
 *   头(BE): metadataSize/fileSize/version/dataOffset + endian(0=LE) + reserved3
 *   元数据(LE): unityVersion\0 targetPlatform enableTypeTree typeCount
 *     [type: classID isStripped scriptTypeIndex(i16) oldTypeHash(16)]
 *     objectCount [align4] pathID(i64) storedByteStart(u32) byteSize(u32) typeID(i32)
 *     scriptCount externalsCount userInformation\0
 *   dataOffset 对齐 4，其后为对象数据（storedByteStart 相对 dataOffset）
 */
fun buildSerializedFile(objBytes: ByteArray): ByteArray =
    buildSerializedFile(objBytes, 1)

fun buildSerializedFile(objBytes: ByteArray, classID: Int): ByteArray {
    val meta = java.io.ByteArrayOutputStream()
    fun le64(v: Long) { for (i in 0 until 8) meta.write((v ushr (8 * i)).toInt()) }
    fun le32(v: Int) { meta.write(v and 0xFF); meta.write(v shr 8 and 0xFF); meta.write(v shr 16 and 0xFF); meta.write(v shr 24 and 0xFF) }
    fun le16(v: Int) { meta.write(v and 0xFF); meta.write(v shr 8 and 0xFF) }
    fun str(s: String) { meta.write(s.toByteArray(Charsets.US_ASCII)); meta.write(0) }

    // ---- 元数据（v17, LittleEndian, enableTypeTree=false）----
    str("2019.4.1f1")                       // unityVersion
    le32(19)                                // targetPlatform
    meta.write(0)                           // enableTypeTree = false
    le32(1)                                 // typeCount
    // SerializedType: classID / isStripped / scriptTypeIndex / oldTypeHash
    le32(classID)                           // classID（默认 GameObject；Mesh 替换测试传 43）
    meta.write(0)                           // m_IsStrippedType
    le16(-1)                                // m_ScriptTypeIndex
    repeat(16) { meta.write(0x11) }         // m_OldTypeHash
    le32(1)                                 // objectCount
    // 对象表前对齐 4（解析器 readInt64 前调用 alignStream()，按绝对偏移对齐；
    // 头部为 20 字节：4 字段×4 + endian 1 + reserved 3）
    while ((20 + meta.size()) % 4 != 0) meta.write(0)
    le64(1L)                                // pathID = 1（v17: i64）
    le32(0)                                 // storedByteStart（相对 dataOffset）
    le32(objBytes.size)                     // byteSize
    le32(0)                                 // typeID（m_Types 索引）
    le32(0)                                 // scriptCount
    le32(0)                                 // externalsCount
    meta.write(0)                           // userInformation（空串）
    val metadata = meta.toByteArray()

    // dataOffset：metadataEnd（20 + metadata.size）之后对齐 4
    val dataOffset = ((20 + metadata.size + 3) / 4) * 4
    val pad = dataOffset - 20 - metadata.size

    val out = java.io.ByteArrayOutputStream()
    fun be32(v: Int) { out.write(v ushr 24); out.write(v ushr 16); out.write(v ushr 8); out.write(v) }
    be32(metadata.size + 20)                // metadataSize（头部+元数据）
    be32(dataOffset + objBytes.size)        // fileSize（= 文件总长）
    be32(17)                                // version
    be32(dataOffset)                        // dataOffset
    out.write(0)                            // endianess = LittleEndian
    repeat(3) { out.write(0) }              // reserved
    out.write(metadata)
    repeat(pad) { out.write(0) }
    out.write(objBytes)
    return out.toByteArray()
}

/** 回填对象表 storedByteStart（构造时无法预知，解析方读取的是正确值即可——此处恒 0） */

/**
 * 多对象多类型 SerializedFile：每项 (classID, 对象字节)，pathID 依次 1..N。
 * 类型表按 classID 去重；对象数据按 4 字节对齐依次排布
 * （首对象 storedByteStart=0 满足 16 对齐，重写器沿用原始对齐策略）。
 */
fun buildSerializedFileMulti(objects: List<Pair<Int, ByteArray>>): ByteArray {
    require(objects.isNotEmpty()) { "至少一个对象" }

    // 类型表：classID → 类型索引（去重，保持出现顺序）
    val typeIndex = LinkedHashMap<Int, Int>()
    for ((classID, _) in objects) {
        if (classID !in typeIndex) typeIndex[classID] = typeIndex.size
    }

    // 对象数据布局：首对象偏移 0，后续对象 4 字节对齐
    val starts = IntArray(objects.size)
    var cursor = 0
    for (i in objects.indices) {
        if (i > 0) cursor = (cursor + 3) / 4 * 4
        starts[i] = cursor
        cursor += objects[i].second.size
    }
    val dataSize = starts.last() + objects.last().second.size

    val meta = java.io.ByteArrayOutputStream()
    fun le64(v: Long) { for (i in 0 until 8) meta.write((v ushr (8 * i)).toInt()) }
    fun le32(v: Int) { meta.write(v and 0xFF); meta.write(v shr 8 and 0xFF); meta.write(v shr 16 and 0xFF); meta.write(v shr 24 and 0xFF) }
    fun le16(v: Int) { meta.write(v and 0xFF); meta.write(v shr 8 and 0xFF) }
    fun str(s: String) { meta.write(s.toByteArray(Charsets.US_ASCII)); meta.write(0) }

    str("2019.4.1f1")                       // unityVersion
    le32(19)                                // targetPlatform
    meta.write(0)                           // enableTypeTree = false
    le32(typeIndex.size)                    // typeCount
    for ((classID, _) in typeIndex) {
        le32(classID)                       // classID
        meta.write(0)                       // m_IsStrippedType
        le16(-1)                            // m_ScriptTypeIndex
        repeat(16) { meta.write(0x11) }     // m_OldTypeHash
    }
    le32(objects.size)                      // objectCount
    while ((20 + meta.size()) % 4 != 0) meta.write(0)
    for (i in objects.indices) {
        le64((i + 1).toLong())              // pathID = 1..N
        le32(starts[i])                     // storedByteStart（相对 dataOffset）
        le32(objects[i].second.size)        // byteSize
        le32(typeIndex[objects[i].first]!!) // typeID（m_Types 索引）
    }
    le32(0)                                 // scriptCount
    le32(0)                                 // externalsCount
    meta.write(0)                           // userInformation（空串）
    val metadata = meta.toByteArray()

    val dataOffset = ((20 + metadata.size + 3) / 4) * 4
    val pad = dataOffset - 20 - metadata.size

    val out = java.io.ByteArrayOutputStream()
    fun be32(v: Int) { out.write(v ushr 24); out.write(v ushr 16); out.write(v ushr 8); out.write(v) }
    be32(metadata.size + 20)                // metadataSize（头部+元数据）
    be32(dataOffset + dataSize)             // fileSize（= 文件总长）
    be32(17)                                // version
    be32(dataOffset)                        // dataOffset
    out.write(0)                            // endianess = LittleEndian
    repeat(3) { out.write(0) }              // reserved
    out.write(metadata)
    repeat(pad) { out.write(0) }
    // 对象数据（按 starts 布局，间隙补 0）
    for (i in objects.indices) {
        val gap = starts[i] - (if (i == 0) 0 else starts[i - 1] + objects[i - 1].second.size)
        repeat(gap) { out.write(0) }
        out.write(objects[i].second)
    }
    return out.toByteArray()
}

/** 构造标准 UnityFS bundle */
fun buildUnityFs(entries: List<Pair<String, ByteArray>>): ByteArray {
    val data = java.io.ByteArrayOutputStream()
    val entryTable = ArrayList<Triple<Long, Long, String>>()
    var off = 0L
    for ((name, bytes) in entries) {
        entryTable.add(Triple(off, bytes.size.toLong(), name))
        data.write(bytes)
        off += bytes.size
    }
    val payload = data.toByteArray()

    val info = java.io.ByteArrayOutputStream()
    fun u16(v: Int) { info.write(v ushr 8); info.write(v) }
    fun u32(v: Int) { info.write(v ushr 24); info.write(v ushr 16); info.write(v ushr 8); info.write(v) }
    fun i64(v: Long) { for (i in 7 downTo 0) info.write((v ushr (i * 8)).toInt()) }
    repeat(16) { info.write(0) }
    val compressedBlock = LZ4.encode(payload)
    u32(1)
    u32(payload.size); u32(compressedBlock.size); u16(2)   // 单块 LZ4
    u32(entryTable.size)
    for ((o, s, name) in entryTable) { i64(o); i64(s); u32(0); info.write(name.toByteArray()); info.write(0) }
    val infoPlain = info.toByteArray()
    val infoStored = LZ4.encode(infoPlain)

    val out = java.io.ByteArrayOutputStream()
    fun ou32(v: Int) { out.write(v ushr 24); out.write(v ushr 16); out.write(v ushr 8); out.write(v) }
    fun oi64(v: Long) { for (i in 7 downTo 0) out.write((v ushr (i * 8)).toInt()) }
    out.write("UnityFS".toByteArray()); out.write(0)
    ou32(6)
    out.write("5.x.x".toByteArray()); out.write(0)
    out.write("2019.4.1f1".toByteArray()); out.write(0)
    val sizePos = out.size()
    oi64(0)
    ou32(infoStored.size); ou32(infoPlain.size); ou32(2 or 0x40)
    out.write(infoStored)
    out.write(compressedBlock)
    val bytes = out.toByteArray()
    for (i in 0 until 8) bytes[sizePos + i] = ((bytes.size.toLong() ushr (8 * (7 - i))) and 0xFF).toByte()
    return bytes
}

private fun noise(size: Int): ByteArray = ByteArray(size) { ((it * 31 + 7) % 251).toByte() }
}

private fun noise(size: Int): ByteArray = ByteArray(size) { ((it * 31 + 7) % 251).toByte() }

class ReplaceReloadE2eTest {

    @Test
    fun `替换后另存为bin重新加载资产文件可解析`() {
        // ---------- 1. 原始文件：bundle 内含一个真实 SerializedFile ----------
        val originalObj = noise(300)
        val serialized = TestAssets.buildSerializedFile(originalObj)
        val bundleBytes = TestAssets.buildUnityFs(listOf("CAB-e2etest" to serialized))

        val mgr1 = AssetsManager()
        mgr1.loadFile("game.bundle", bundleBytes)
        //（对象数据是随机字节，GameObject 解析失败属对象级容错的预期行为，不视为容器问题）
        assertEquals(1, mgr1.assetsFileList.size, "原始加载应解析出 1 个资产文件")

        val sf = mgr1.assetsFileList[0]
        assertEquals(1, sf.m_Objects.size)
        assertEquals(1L, sf.m_Objects[0].m_PathID)
        assertEquals(originalObj.size.toLong(), sf.m_Objects[0].byteSize)

        // ---------- 2. 替换对象（尺寸变化，模拟贴图替换）----------
        val replacedObj = noise(500)
        val rewritten = SerializedFileRewriter.rewrite(sf, mapOf(1L to replacedObj))

        // ---------- 3. 回填 bundle 条目并重打包（= AssetReplacer 第 5 步）----------
        val bundle = BundleFile(bundleBytes, "game.bundle")
        bundle.fileList[0].data = rewritten
        val saved = BundleRepacker.repack(bundle)

        // ---------- 4. 以 .bin 后缀重新加载（用户场景）----------
        val mgr2 = AssetsManager()
        mgr2.loadFile("saved.bin", saved)
        // ★ 核心断言：另存文件重新加载必须解析出资产文件（用户遇到的"加载不出资产"）
        assertEquals(1, mgr2.assetsFileList.size, "另存 .bin 应解析出 1 个资产文件")

        val sf2 = mgr2.assetsFileList[0]
        assertEquals(1, sf2.m_Objects.size, "对象表应完整")
        assertEquals(1L, sf2.m_Objects[0].m_PathID)
        assertEquals(replacedObj.size.toLong(), sf2.m_Objects[0].byteSize, "替换后对象大小应同步")

        // 对象数据区内容 = 替换后字节（验证 byteStart 定位正确）
        val objStart = sf2.m_Objects[0].byteStart.toInt()
        val objBytes = sf2.fileBytes.copyOfRange(objStart, objStart + replacedObj.size)
        assertTrue(objBytes.contentEquals(replacedObj), "对象数据应与替换后字节一致")
    }

    @Test
    fun `旧版另存坏文件自动修复后可加载`() {
        // 模拟 v1.3.3 另存文件：SerializedFile 条目 fileSize 字段被误按小端写入
        val originalObj = noise(300)
        val serialized = TestAssets.buildSerializedFile(originalObj)
        // 模拟旧 bug：头部 fileSize（大端字段）按小端写入
        val corrupted = serialized.copyOf()
        for (i in 0 until 4) {
            corrupted[4 + i] = ((serialized.size ushr (8 * i)) and 0xFF).toByte()
        }

        val mgr1 = AssetsManager()
        mgr1.loadFile("game.bundle", TestAssets.buildUnityFs(listOf("CAB-old" to corrupted)))
        // 兼容修复：嗅探失败但满足字节序翻转症状 → 自动修复加载
        assertEquals(1, mgr1.assetsFileList.size, "旧版坏文件应被自动修复并加载出资产文件")
        assertEquals(1, mgr1.assetsFileList[0].m_Objects.size)
        assertEquals(originalObj.size.toLong(), mgr1.assetsFileList[0].m_Objects[0].byteSize)
    }

    @Test
    fun `不替换直接重打包往返保持等价`() {
        val originalObj = noise(300)
        val serialized = TestAssets.buildSerializedFile(originalObj)
        val bundleBytes = TestAssets.buildUnityFs(listOf("CAB-roundtrip" to serialized))

        val mgr1 = AssetsManager()
        mgr1.loadFile("a.bundle", bundleBytes)
        assertEquals(1, mgr1.assetsFileList.size)

        val bundle = BundleFile(bundleBytes, "a.bundle")
        val saved = BundleRepacker.repack(bundle)

        val mgr2 = AssetsManager()
        mgr2.loadFile("b.bin", saved)
        assertEquals(1, mgr2.assetsFileList.size)
        assertEquals(
            mgr1.assetsFileList[0].m_Objects[0].byteSize,
            mgr2.assetsFileList[0].m_Objects[0].byteSize
        )
    }
}

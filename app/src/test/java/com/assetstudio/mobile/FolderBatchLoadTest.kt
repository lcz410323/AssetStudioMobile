package com.assetstudio.mobile

import com.assetstudio.mobile.core.manager.AssetsManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * 文件夹批量加载核心链路回归测试。
 *
 * 批量模式将 loadFile 拆分为 loadFileDeferred（加载）+ finishLoading
 * （统一 readAssets/processAssets），本测试验证：
 * 1. 拆分后的资产文件与对象表状态与原 loadFile 单文件行为一致
 *    （对象字节为随机数据时具体类构造失败属对象级容错预期，不算容器问题，
 *     此处断言的是对象表 m_Objects 的解析结果）；
 * 2. deferred 阶段不构造对象（批量加载避免逐文件 O(N²) 的关键）；
 * 3. 多文件逐个 deferred 加载后统一收尾，全部文件与对象表可见；
 * 4. retainedBytes() 与实际驻留字节一致（文件夹内存软上限的依据）；
 * 5. 单文件数据损坏时 deferred 流程记录错误但不抛出（批量容错前提）。
 */
class FolderBatchLoadTest {

    private fun minimalObject(): ByteArray =
        ByteArray(300) { ((it * 31 + 7) % 251).toByte() }

    @Test
    fun `deferred 加载加统一收尾与原 loadFile 结果一致`() {
        val data = TestAssets.buildSerializedFile(minimalObject())

        // 原路径：loadFile（内部 = deferred + readAssets + processAssets）
        val direct = AssetsManager()
        direct.loadFile("direct.assets", data)

        // 批量路径：deferred + finishLoading
        val batch = AssetsManager()
        batch.loadFileDeferred("batch.assets", data)
        assertEquals(0, batch.assetsFileList.sumOf { it.objects.size },
            "deferred 阶段不应构造对象（避免逐文件 O(N²)）")
        batch.finishLoading()

        assertEquals(direct.assetsFileList.size, batch.assetsFileList.size, "序列化文件数应一致")
        assertEquals(
            direct.assetsFileList.sumOf { it.m_Objects.size },
            batch.assetsFileList.sumOf { it.m_Objects.size },
            "对象表条目数应一致"
        )
        assertTrue(
            batch.assetsFileList.sumOf { it.m_Objects.size } > 0,
            "对象表应完成解析"
        )
    }

    @Test
    fun `多文件逐个 deferred 加载全部可见且驻留字节正确`() {
        val manager = AssetsManager()
        val perFile = TestAssets.buildSerializedFile(minimalObject())
        repeat(5) { i ->
            manager.loadFileDeferred("file$i.assets", perFile)
        }
        manager.finishLoading()

        assertEquals(5, manager.assetsFileList.size, "5 个文件应全部登记")
        assertEquals(5, manager.assetsFileList.sumOf { it.m_Objects.size }, "每文件对象表 1 条")

        val expected = perFile.size.toLong() * 5
        assertEquals(expected, manager.retainedBytes(), "驻留字节应等于 5 份文件字节总和")
    }

    @Test
    fun `损坏文件 deferred 加载记录错误不抛出`() {
        val manager = AssetsManager()
        val garbage = ByteArray(64) { 0x55 }
        manager.loadFileDeferred("garbage.bin", garbage)
        manager.loadFileDeferred("good.assets", TestAssets.buildSerializedFile(minimalObject()))
        manager.finishLoading()

        // 垃圾数据作为资源文件登记或报错均可，但不能中断后续文件加载
        assertTrue(
            manager.assetsFileList.any { it.fileName.equals("good.assets", ignoreCase = true) },
            "损坏文件之后的正常文件仍应加载成功"
        )
        assertTrue(manager.retainedBytes() > 0)
    }

    @Test
    fun `内存防线触发时 readAssets 停止构造并可续调恢复`() {
        val manager = AssetsManager()
        val perFile = TestAssets.buildSerializedFile(minimalObject())
        repeat(3) { i -> manager.loadFileDeferred("file$i.assets", perFile) }

        // 防线立即触发：报告全部未构造；文件登记与对象表保留（数据未丢）
        val remaining = manager.finishLoading { true }
        assertEquals(3, remaining, "防线触发时应报告 3 个文件未构造")
        assertEquals(3, manager.assetsFileList.size, "文件登记不应被清除")
        assertEquals(3, manager.assetsFileList.sumOf { it.m_Objects.size },
            "对象表应保留（续调可恢复）")

        // 续调（无防线）：全部文件构造完成
        val remaining2 = manager.finishLoading()
        assertEquals(0, remaining2, "续调应构造全部文件")
    }

    @Test
    fun `内存防线在首个文件构造后触发时保留已完成部分`() {
        val manager = AssetsManager()
        val perFile = TestAssets.buildSerializedFile(minimalObject())
        repeat(3) { i -> manager.loadFileDeferred("file$i.assets", perFile) }

        // 防线在每文件「构造前」检查：第 1 次放行（文件1构造），
        // 第 2 次触发停止 → 文件1已构造，文件2/3 未构造
        var checks = 0
        val remaining = manager.finishLoading {
            checks++
            checks >= 2
        }
        assertEquals(2, remaining, "应报告剩余 2 个文件未构造")
        assertTrue(manager.retainedBytes() > 0)

        // 续调完成剩余文件
        val remaining2 = manager.finishLoading()
        assertEquals(0, remaining2, "续调应完成剩余文件")
    }
}

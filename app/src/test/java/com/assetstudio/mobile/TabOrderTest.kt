package com.assetstudio.mobile

import com.assetstudio.mobile.ui.list.TabOrderStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * 资产列表分类 tab 长按置顶排序的核心逻辑测试。
 */
class TabOrderTest {

    // ---------- moveToFront：长按置顶 ----------

    @Test
    fun `长按某分类移到最前`() {
        val result = TabOrderStore.moveToFront(listOf("贴图", "材质", "网格"), "材质")
        assertEquals(listOf("材质", "贴图", "网格"), result)
    }

    @Test
    fun `连续长按形成顺序（最后长按的最前）`() {
        var order = listOf("贴图", "材质", "网格", "音频")
        order = TabOrderStore.moveToFront(order, "网格") // 网格置顶
        assertEquals(listOf("网格", "贴图", "材质", "音频"), order)
        order = TabOrderStore.moveToFront(order, "音频") // 音频再置顶
        assertEquals(listOf("音频", "网格", "贴图", "材质"), order)
    }

    @Test
    fun `长按已在最前的分类顺序不变`() {
        val result = TabOrderStore.moveToFront(listOf("贴图", "材质"), "贴图")
        assertEquals(listOf("贴图", "材质"), result)
    }

    @Test
    fun `长按不存在的类型容错插入最前`() {
        val result = TabOrderStore.moveToFront(listOf("贴图", "材质"), "网格")
        assertEquals(listOf("网格", "贴图", "材质"), result)
    }

    // ---------- applyOrder：按保存顺序还原 ----------

    @Test
    fun `按保存顺序重排（稳定）`() {
        val types = listOf("贴图", "材质", "网格", "音频")
        val saved = listOf("音频", "网格")
        val result = TabOrderStore.applyOrder(types, saved)
        assertEquals(listOf("音频", "网格", "贴图", "材质"), result)
    }

    @Test
    fun `保存顺序含已不存在的类型时被忽略`() {
        // 换了文件加载：保存了"视频"但当前类型集中没有
        val types = listOf("贴图", "材质")
        val saved = listOf("视频", "材质")
        val result = TabOrderStore.applyOrder(types, saved)
        assertEquals(listOf("材质", "贴图"), result)
        assertTrue("不应出现不存在的类型", "视频" !in result)
    }

    @Test
    fun `无保存顺序时保持默认顺序`() {
        val types = listOf("贴图", "材质", "网格")
        val result = TabOrderStore.applyOrder(types, emptyList())
        assertEquals(types, result)
    }

    @Test
    fun `完整流程：置顶到保存再到还原`() {
        // 模拟用户操作：默认 [贴图,材质,网格]，长按"网格"再长按"贴图"
        var display = listOf("贴图", "材质", "网格")
        display = TabOrderStore.moveToFront(display, "网格")   // → [网格,贴图,材质]
        display = TabOrderStore.moveToFront(display, "贴图")   // → [贴图,网格,材质]
        // "保存"的就是这个完整顺序；下次打开还原
        val restored = TabOrderStore.applyOrder(display, display)
        assertEquals(listOf("贴图", "网格", "材质"), restored)
    }
}

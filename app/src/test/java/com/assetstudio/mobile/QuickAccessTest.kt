package com.assetstudio.mobile

import com.assetstudio.mobile.ui.browser.QuickAccessStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * 快捷访问（收藏目录）核心逻辑测试：merge 纯函数（去重 / 最新在前 / 容量上限）。
 */
class QuickAccessTest {

    @Test
    fun `新增目录放在最前`() {
        val result = QuickAccessStore.merge(listOf("/a", "/b"), "/c")
        assertEquals(listOf("/c", "/a", "/b"), result)
    }

    @Test
    fun `重复添加同一目录不产生重复且保持原顺序`() {
        val current = listOf("/a", "/b")
        val result = QuickAccessStore.merge(current, "/a")
        assertEquals(listOf("/a", "/b"), result)
    }

    @Test
    fun `超过容量上限时淘汰最旧的收藏`() {
        // 装满 12 条：列表约定 index 0 最新、末尾最旧（/d01 最新 … /d12 最旧）
        val full = (1..QuickAccessStore.MAX_ENTRIES).map { "/d%02d".format(it) }
        // 再添加 /new：最新在前，末尾的 /d12（最旧）被淘汰
        val result = QuickAccessStore.merge(full, "/new")
        assertEquals(QuickAccessStore.MAX_ENTRIES, result.size)
        assertEquals("/new", result.first())
        assertTrue("/d12（最旧）应被淘汰", "/d12" !in result)
        assertTrue("/d01 应保留", "/d01" in result)
    }

    @Test
    fun `空列表添加`() {
        val result = QuickAccessStore.merge(emptyList(), "/sdcard/Download")
        assertEquals(listOf("/sdcard/Download"), result)
    }
}

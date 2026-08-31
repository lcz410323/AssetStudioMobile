package com.assetstudio.mobile

import com.assetstudio.mobile.replace.BatchTextureMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * 批量替换文件名自动匹配测试：
 * - 精确匹配（大小写/下划线/空格差异）
 * - 包含匹配（"(实例)"后缀等）与歧义拒绝
 * - 重复贴图名 / 未匹配文件统计
 */
class BatchTextureMatcherTest {

    @Test
    fun exactMatchIgnoresCaseAndSeparators() {
        val plan = BatchTextureMatcher.match(
            listOf("hero_skin", "UI BG", "BossFace"),
            listOf("Hero_Skin.png", "ui-bg.jpg", "bossface.webp")
        )
        assertEquals(3, plan.matchedCount)
        assertEquals(0, plan.unmatchedTextures.size)
        assertEquals(0, plan.unmatchedFiles.size)
        // 配对正确
        assertTrue(plan.matches.any { it.textureName == "hero_skin" && it.fileName == "Hero_Skin.png" })
        assertTrue(plan.matches.any { it.textureName == "UI BG" && it.fileName == "ui-bg.jpg" })
        assertTrue(plan.matches.any { it.textureName == "BossFace" && it.fileName == "bossface.webp" })
    }

    @Test
    fun containmentMatchHandlesInstanceSuffix() {
        // Unity 重复资源名常带 "(instance)" / " 1" 后缀
        val plan = BatchTextureMatcher.match(
            listOf("sword_icon (instance)"),
            listOf("sword_icon.png")
        )
        assertEquals(1, plan.matchedCount)
        assertEquals("sword_icon.png", plan.matches[0].fileName)
    }

    @Test
    fun ambiguousContainmentIsRejected() {
        // 两个候选都能包含 "skin" → 不唯一 → 不匹配
        val plan = BatchTextureMatcher.match(
            listOf("skin"),
            listOf("skin_blue.png", "skin_red.png")
        )
        assertEquals(0, plan.matchedCount)
        assertEquals(1, plan.unmatchedTextures.size)
        assertEquals(2, plan.unmatchedFiles.size)
    }

    @Test
    fun duplicateTextureNamesEachGetAMatch() {
        val plan = BatchTextureMatcher.match(
            listOf("tile", "tile", "tile"),
            listOf("tile.png", "tile 1.png", "tile_2.png")
        )
        // 精确匹配 1 个 + 包含匹配 2 个（"tile 1"/"tile_2" 归一化后都包含 "tile"，各自唯一候选时逐个配对）
        assertEquals(3, plan.matchedCount)
        assertEquals(0, plan.unmatchedFiles.size)
    }

    @Test
    fun unmatchedItemsAreReported() {
        val plan = BatchTextureMatcher.match(
            listOf("aaa", "bbb", "ccc"),
            listOf("aaa.png", "ddd.png")
        )
        assertEquals(1, plan.matchedCount)
        assertEquals(listOf("bbb", "ccc"), plan.unmatchedTextures)
        assertEquals(listOf("ddd.png"), plan.unmatchedFiles)
    }

    @Test
    fun emptyTextureNameNeverMatches() {
        val plan = BatchTextureMatcher.match(
            listOf("", "a"),
            listOf("x.png")
        )
        assertEquals(0, plan.matchedCount)
        assertEquals(2, plan.unmatchedTextures.size)
    }

    @Test
    fun extensionIsStrippedBeforeMatching() {
        val plan = BatchTextureMatcher.match(
            listOf("photo.tar"),
            listOf("photo.png")
        )
        // 贴图名不会带 .tar；此用例验证文件扩展名确实被剥离
        // photo.tar 归一化 = "phototar"，photo.png 去扩展名归一化 = "photo"
        // 包含："phototar".contains("photo") = true 且唯一 → 匹配
        assertEquals(1, plan.matchedCount)
    }

    @Test
    fun normalizeKeepsOnlyAlphanumerics() {
        // 纯归一化：小写 + 仅保留字母数字（扩展名剥离在 match() 内完成，见下个用例）
        assertEquals("heroskinv2", BatchTextureMatcher.normalize("Hero-Skin (v2)_!"))
        assertEquals("", BatchTextureMatcher.normalize("--- ___"))
    }
}

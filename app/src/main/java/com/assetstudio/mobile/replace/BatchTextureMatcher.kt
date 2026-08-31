package com.assetstudio.mobile.replace

/*
 * 批量替换的"贴图名 ↔ 图片文件名"自动匹配器。
 *
 * 美化包素材通常按贴图原名保存（hero_skin.png ↔ 贴图 "hero_skin"），
 * 但两边命名常有细微差异：大小写、空格/下划线、"(实例)" 后缀、
 * 重复名自动加的 " 1" 编号等。匹配策略：
 *
 * 1. 归一化：小写 + 仅保留字母数字（剔除所有分隔符与空白）；
 * 2. 精确匹配：归一化后相等（去扩展名）；
 * 3. 包含匹配：一方归一化后包含另一方，且候选唯一时接受
 *    （处理 "HeroSkin (instance)" ↔ "heroskin.png" 这类后缀差异；
 *    候选不唯一宁可不动手，避免错误替换）。
 */
object BatchTextureMatcher {

    /** 一条匹配：贴图名 → 图片文件名 */
    class Match(val textureName: String, val fileName: String)

    /** 匹配计划 */
    class Plan(
        /** 成功配对（textureName → fileName） */
        val matches: List<Match>,
        /** 没找到图片的贴图名 */
        val unmatchedTextures: List<String>,
        /** 没被任何贴图使用的图片文件名 */
        val unmatchedFiles: List<String>
    ) {
        val matchedCount: Int get() = matches.size
    }

    /** 归一化：小写 + 去掉所有非字母数字字符 */
    fun normalize(name: String): String = buildString {
        for (c in name) if (c.isLetterOrDigit()) append(c.lowercaseChar())
    }

    /**
     * 匹配贴图名列表与图片文件名列表。
     * @param textureNames 贴图显示名（可重复；同名贴图各自独立参与匹配）
     * @param fileNames 图片文件名（含扩展名）
     */
    fun match(textureNames: List<String>, fileNames: List<String>): Plan {
        // 文件名去扩展名后归一化；保留原始名用于展示
        val files = fileNames.map { it to normalize(it.substringBeforeLast('.', it)) }
            .filter { it.second.isNotEmpty() }

        // 归一化名 → 文件索引（同名文件全部保留，先到先得）
        val byNorm = HashMap<String, MutableList<Int>>()
        files.forEachIndexed { i, f -> byNorm.getOrPut(f.second) { ArrayList() }.add(i) }

        val used = HashSet<Int>()
        val matches = ArrayList<Match>()
        val unmatched = ArrayList<String>()

        // ---------- Pass 1: 精确匹配 ----------
        for (t in textureNames) {
            val n = normalize(t)
            val cand = if (n.isEmpty()) null else byNorm[n]?.firstOrNull { it !in used }
            if (cand != null) {
                used.add(cand)
                matches.add(Match(t, files[cand].first))
            } else {
                unmatched.add(t)
            }
        }

        // ---------- Pass 2: 包含匹配 ----------
        // 候选唯一时直接配对；多个候选时仅当全部是"数字编号变体"
        // （归一化名 = 基名 + 纯数字，如 tile1 / tile2，来自 Unity 重复名导出）
        // 才按序取第一个，否则宁可不动手（避免错误替换）。
        val stillUnmatched = ArrayList<String>()
        for (t in unmatched) {
            val n = normalize(t)
            var matchIdx: Int? = null
            if (n.length >= 2) {
                val cands = files.indices.filter { i ->
                    i !in used && (files[i].second.contains(n) || n.contains(files[i].second))
                }
                matchIdx = when {
                    cands.isEmpty() -> null
                    cands.size == 1 -> cands[0]
                    else -> {
                        val allVariants = cands.all { i ->
                            val fn = files[i].second
                            fn.length > n.length && fn.startsWith(n) &&
                                fn.drop(n.length).all { it.isDigit() }
                        }
                        if (allVariants) cands[0] else null
                    }
                }
            }
            if (matchIdx != null) {
                used.add(matchIdx)
                matches.add(Match(t, files[matchIdx].first))
            } else {
                stillUnmatched.add(t)
            }
        }

        val unmatchedFiles = files.filterIndexed { i, _ -> i !in used }.map { it.first }
        return Plan(matches, stillUnmatched, unmatchedFiles)
    }
}

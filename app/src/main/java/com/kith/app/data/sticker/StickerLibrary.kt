package com.kith.app.data.sticker

import com.kith.app.core.Ids
import com.kith.app.domain.Sticker

/**
 * 单条表情的解析结果。
 *
 * 系统表情用 emoji 字形渲染，用户上传的用图片渲染。混用两种形态是有意的：
 * emoji 在系统字体渲染下就是用户平时在微信里看到的那个样子，**观感天然是"真"的**，
 * 不需要预先塞一堆位图进包体，也不会出现"画出来的假表情"那种廉价感。
 */
sealed interface StickerResolution {
    data class Image(val sticker: Sticker) : StickerResolution

    data class Emoji(
        val glyph: String,
        /** 命中的情绪词，用于日志与调试 */
        val emotion: String,
    ) : StickerResolution
}

/**
 * 系统内置表情表。
 *
 * 情绪词取自 human-chat-protocol 的《内置情绪词参考表》，覆盖 12 个类别。
 * 每个类别给多个字形，命中时随机取一个 —— 避免 AI 每次说「笑哭」都发同一张。
 */
object SystemStickers {

    val emotionToGlyphs: Map<String, List<String>> = mapOf(
        // 开心
        "大笑" to listOf("😄", "😆"), "偷笑" to listOf("🤭", "😏"), "嘿嘿" to listOf("😁", "😏"),
        "得意" to listOf("😎", "😏"), "开心" to listOf("😊", "😄"), "耶" to listOf("✌️", "🙌"),
        "雀跃" to listOf("🤗", "🙌"),
        // 笑哭
        "笑哭" to listOf("😂", "🤣"), "笑尿" to listOf("🤣", "😹"), "笑死" to listOf("😂", "💀"),
        "笑不活了" to listOf("🤣", "🫠"), "笑到打鸣" to listOf("🤣", "🐔"),
        // 拥抱安慰
        "抱抱" to listOf("🫂", "🤗"), "摸摸" to listOf("🫳", "🥺"), "拍拍" to listOf("🫂", "🤚"),
        "安慰" to listOf("🥺", "🫂"), "心疼" to listOf("🥺", "💔"),
        // 点赞
        "点赞" to listOf("👍", "👏"), "棒" to listOf("👍", "⭐"), "牛" to listOf("🐮", "💪"),
        "666" to listOf("🔥", "👏"), "太强了" to listOf("💪", "🔥"), "服" to listOf("🙇", "👍"),
        // 尴尬
        "尴尬" to listOf("😅", "😬"), "汗" to listOf("😓", "💧"), "捂脸" to listOf("🤦", "🫣"),
        "无语" to listOf("😑", "🙃"), "裂开" to listOf("🫠", "💥"),
        // 委屈
        "委屈" to listOf("🥺", "😞"), "可怜" to listOf("🥺", "😢"), "哭" to listOf("😭", "😢"),
        "嘤嘤" to listOf("🥺", "😿"), "泪目" to listOf("🥹", "😢"),
        // 调侃
        "狗头" to listOf("🐶", "🐕"), "吃瓜" to listOf("🍉", "👀"), "看热闹" to listOf("👀", "🍿"),
        "坏笑" to listOf("😏", "😼"), "阴阳怪气" to listOf("🙃", "😏"),
        // 生气
        "生气" to listOf("😠", "😤"), "怒" to listOf("😡", "💢"), "哼" to listOf("😤", "😾"),
        "不服" to listOf("😤", "🙄"), "气鼓鼓" to listOf("😤", "😠"),
        // 可爱
        "比心" to listOf("🫰", "💕"), "飞吻" to listOf("😘", "💋"), "萌萌哒" to listOf("🥰", "🐰"),
        "可爱" to listOf("🥰", "😊"), "啾咪" to listOf("😘", "✨"),
        // 思考
        "思考" to listOf("🤔", "🧐"), "嗯" to listOf("🤔", "😶"), "让我想想" to listOf("🤔", "💭"),
        "琢磨" to listOf("🧐", "🤔"), "沉吟" to listOf("😶", "🤔"),
        // 惊讶
        "哇" to listOf("😲", "🤩"), "卧槽" to listOf("😱", "🤯"), "震惊" to listOf("😱", "🤯"),
        "不会吧" to listOf("😳", "😲"), "离谱" to listOf("🤯", "🫠"),
        // 再见
        "拜拜" to listOf("👋", "🙋"), "溜了" to listOf("🏃", "💨"), "撤" to listOf("🏃", "👋"),
        "跑路" to listOf("🏃", "💨"),
    )

    /** 兜底表情：没匹配上情绪词时用，而不是显示一个空白框。 */
    val FALLBACK_GLYPHS = listOf("🙂", "😊", "💬")

    val allEmotions: List<String> get() = emotionToGlyphs.keys.toList()

    /** 情绪词 → 字形。带一级模糊匹配（包含关系）。 */
    fun resolve(emotion: String): List<String>? {
        val key = emotion.trim()
        if (key.isEmpty()) return null
        emotionToGlyphs[key]?.let { return it }

        // 模糊：情绪词里有包含关系时取最长的那个键
        val fuzzy = emotionToGlyphs.keys
            .filter { it.contains(key) || key.contains(it) }
            .maxByOrNull { it.length }
        return fuzzy?.let { emotionToGlyphs[it] }
    }
}

/**
 * 表情包库。
 *
 * 匹配策略完全按 protocol-spec 的三级规则：
 *  1. **精确匹配**：用户在 `emotions` 数组里完全等于情绪词的记录；
 *  2. **模糊匹配**：分词/包含匹配；
 *  3. **兜底**：都没命中就用系统 emoji，保证界面上永远有东西出现。
 *
 * 每个社会一份独立的库（存在该社会的 `stickers.json`），因为不同社会的
 * 角色性格、题材可能完全不同，共用一套表情会很违和。
 */
class StickerLibrary {

    /**
     * 解析一个 `<sticker emotion="..." />` 标签。
     *
     * @param library 该社会自己的表情库
     */
    fun resolve(
        library: List<Sticker>,
        emotion: String = "",
        stickerId: String = "",
        randomSeed: Int = 0,
    ): StickerResolution {
        // 直接指定了 id：无条件用它
        if (stickerId.isNotEmpty()) {
            library.firstOrNull { it.id == stickerId }?.let {
                return StickerResolution.Image(it)
            }
        }

        val key = emotion.trim()

        // 1. 精确匹配
        if (key.isNotEmpty()) {
            val exact = library.filter { s -> s.emotions.any { it == key } }
            if (exact.isNotEmpty()) return pick(exact, randomSeed)
        }

        // 2. 模糊匹配
        if (key.isNotEmpty()) {
            val fuzzy = library.filter { s ->
                s.emotions.any { e -> e.contains(key) || key.contains(e) }
            }
            if (fuzzy.isNotEmpty()) return pick(fuzzy, randomSeed)
        }

        // 3. 系统 emoji，最后才用固定兜底
        val glyphs = SystemStickers.resolve(key) ?: SystemStickers.FALLBACK_GLYPHS
        val idx = if (randomSeed == 0) 0 else kotlin.math.abs(randomSeed) % glyphs.size
        return StickerResolution.Emoji(glyphs[idx], key)
    }

    private fun pick(candidates: List<Sticker>, randomSeed: Int): StickerResolution {
        if (candidates.size == 1) return StickerResolution.Image(candidates.first())
        // 优先用高频的；同类同样高频时按 seed 打散，避免每次同一张
        val sorted = candidates.sortedByDescending { it.usageCount }
        val top = sorted.take(3)
        val idx = if (randomSeed == 0) 0 else kotlin.math.abs(randomSeed) % top.size
        return StickerResolution.Image(top[idx])
    }

    /** 记一次使用，用于热门排序。 */
    fun bumpUsage(library: List<Sticker>, stickerId: String): List<Sticker> =
        library.map { if (it.id == stickerId) it.copy(usageCount = it.usageCount + 1) else it }

    /** 新增一条表情。 */
    fun add(
        library: List<Sticker>,
        name: String,
        imageUrl: String,
        emotions: List<String>,
        source: String = Sticker.SOURCE_USER,
    ): List<Sticker> = library + Sticker(
        id = Ids.new("stk"),
        name = name,
        imageUrl = imageUrl,
        emotions = emotions.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        source = source,
        usageCount = 0,
        createdAt = System.currentTimeMillis(),
    )

    fun remove(library: List<Sticker>, stickerId: String): List<Sticker> =
        library.filterNot { it.id == stickerId }
}

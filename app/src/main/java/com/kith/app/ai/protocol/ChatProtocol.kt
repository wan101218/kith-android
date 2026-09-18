package com.kith.app.ai.protocol

import com.kith.app.domain.PictureState
import com.kith.app.domain.Segment

/**
 * Human Chat Protocol 解析器。
 *
 * 对应 `human-chat-protocol/references/protocol-spec.md` 里定义的应用层兼容规范。
 * AI 的回复是纯文本，其中内嵌自闭合的 XML 风格标签：
 *
 * ```xml
 * 我天九点也太狠了 <sticker emotion="抱抱" /> 吃饭了吗
 * 你看这个 <chat_image prompt="橘猫晒太阳" caption="今天路过的" /> 是不是超可爱
 * <transfer amount="5.20" to="小明" note="请你喝奶茶" />
 * ```
 *
 * 应用层要做四件事：
 *  1. 识别标签；
 *  2. 把标签从纯文本里剥离（**标签本身不能显示给用户**）；
 *  3. 按顺序拆成文本 / 表情 / 图片 / 转账四种片段；
 *  4. 交给 UI 渲染，生图与表情匹配异步进行。
 *
 * 这套协议不依赖 function calling，也不依赖任何厂商特有的 tool use ——
 * 任何能输出 XML 标签的模型都能用，这对本项目「多厂商任意切换」的定位是刚需。
 */
object ChatProtocol {

    /**
     * 标签匹配。刻意宽松：允许 `<sticker .../>`、`<sticker ... />`、属性用单引号，
     * 以及漏掉斜杠的 `<sticker emotion="笑哭">`。模型输出不可能永远规整，
     * 解析器必须比规范更宽容。
     */
    private val TAG_REGEX = Regex(
        """<(chat_image|sticker|transfer)\b[^>]*?/?>""",
        RegexOption.IGNORE_CASE,
    )

    /** 属性提取，同时支持双引号与单引号。 */
    private val ATTR_REGEX = Regex("""([A-Za-z_][\w-]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    /** 未闭合但已开头的标签片段，用于流式输出时的截断保护。 */
    private val OPEN_TAG_TAIL = Regex("""<[a-z_]*$""", RegexOption.IGNORE_CASE)

    /**
     * 完整解析。返回按原文出现顺序排列的片段列表。
     *
     * 解析失败一律降级为纯文本 —— 需求里明确要求「标签解析失败不能吞掉内容」。
     */
    fun parse(raw: String): List<Segment> {
        if (raw.isEmpty()) return emptyList()

        val out = ArrayList<Segment>(4)
        var cursor = 0

        for (match in TAG_REGEX.findAll(raw)) {
            val plain = raw.substring(cursor, match.range.first)
            // 空白片段也保留：标签之间常靠换行分隔，丢掉会把多段话挤成一坨。
            // 相邻文本片段稍后会被 mergeAdjacentText 合并，UI 侧再统一 trim。
            if (plain.isNotEmpty()) out += Segment.Text(plain)

            parseTag(match.value)?.let { out += it }
                ?: run {
                    // 认不出的标签：原样当文本吐出来，绝不静默丢弃模型输出的内容
                    out += Segment.Text(match.value)
                }
            cursor = match.range.last + 1
        }

        if (cursor < raw.length) {
            val tail = raw.substring(cursor)
            if (tail.isNotEmpty()) out += Segment.Text(tail)
        }

        return mergeAdjacentText(out)
    }

    private fun parseTag(tag: String): Segment? {
        val name = Regex("""^<\s*(chat_image|sticker|transfer)""", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1)?.lowercase() ?: return null

        val attrs = HashMap<String, String>()
        ATTR_REGEX.findAll(tag).forEach { m ->
            val key = m.groupValues[1].lowercase()
            val value = m.groupValues[2].ifEmpty { m.groupValues[3] }
            attrs[key] = value
        }

        return when (name) {
            "sticker" -> {
                val emotion = attrs["emotion"].orEmpty()
                val id = attrs["id"].orEmpty()
                if (emotion.isEmpty() && id.isEmpty()) null
                else Segment.Sticker(emotion = emotion, stickerId = id)
            }

            "chat_image" -> {
                val url = attrs["url"].orEmpty()
                val prompt = attrs["prompt"].orEmpty()
                val query = attrs["search_query"].orEmpty()
                if (url.isEmpty() && prompt.isEmpty() && query.isEmpty()) return null
                Segment.Picture(
                    url = url,
                    prompt = prompt,
                    searchQuery = query,
                    style = attrs["style"].orEmpty(),
                    ratio = attrs["ratio"].orEmpty(),
                    caption = attrs["caption"].orEmpty(),
                    // 带 prompt 或 search_query 的都还没拿到图，标记为生成中，
                    // 由上层异步替换。带 url 的立即可用。
                    state = if (url.isNotEmpty()) PictureState.READY else PictureState.GENERATING,
                )
            }

            "transfer" -> {
                val amount = attrs["amount"]?.toDoubleOrNull() ?: return null
                val to = attrs["to"].orEmpty()
                if (to.isEmpty()) return null
                // 金额异常不渲染卡片，退化为文本由上层提示用户
                if (amount.isNaN() || amount < 0 || amount > 1_000_000) return null
                Segment.Transfer(
                    amount = amount,
                    to = to,
                    note = attrs["note"].orEmpty(),
                )
            }

            else -> null
        }
    }

    /** 相邻的文本片段合并，避免出现一堆碎片气泡。 */
    private fun mergeAdjacentText(list: List<Segment>): List<Segment> {
        if (list.size < 2) return list
        val out = ArrayList<Segment>(list.size)
        for (seg in list) {
            val last = out.lastOrNull()
            if (seg is Segment.Text && last is Segment.Text) {
                out[out.size - 1] = Segment.Text(last.content + seg.content)
            } else {
                out += seg
            }
        }
        return out
    }

    /**
     * 剥掉所有标签，只留文本。
     * 用于推送预览、日志摘要、以及把历史消息喂回模型时保持简洁。
     */
    fun stripTags(raw: String): String =
        TAG_REGEX.replace(raw, " ").replace(Regex("\\s{2,}"), " ").trim()

    /** 列表页/通知里的一句预览，合并所有文本片段。 */
    fun preview(segments: List<Segment>, maxLen: Int = 60): String {
        val text = segments.filterIsInstance<Segment.Text>()
            .joinToString(" ") { it.content.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()
        val extras = buildList {
            if (segments.any { it is Segment.Sticker }) add("[表情]")
            if (segments.any { it is Segment.Picture }) add("[图片]")
            segments.filterIsInstance<Segment.Transfer>().firstOrNull()?.let {
                add("[转账 ¥%.2f]".format(it.amount))
            }
        }
        val combined = (listOf(text) + extras).filter { it.isNotEmpty() }.joinToString(" ")
        return if (combined.length <= maxLen) combined else combined.take(maxLen - 1) + "…"
    }

    /**
     * 流式渲染时的安全截断。
     *
     * 流式输出到一半时，末尾可能是 `<sticker emot` 这样的半截标签。直接解析会把它
     * 当成普通文本闪一下再变成表情，观感很差。这里在解析前把未闭合的尾部切掉，
     * 等标签完整了再显示。
     */
    fun parseStreaming(buffer: String): List<Segment> {
        val cut = OPEN_TAG_TAIL.find(buffer)
        val safe = if (cut != null) buffer.substring(0, cut.range.first) else buffer
        return parse(safe)
    }

    /** 该消息是否触发了生图（需要上层异步补图）。 */
    fun picturesToGenerate(segments: List<Segment>): List<Segment.Picture> =
        segments.filterIsInstance<Segment.Picture>()
            .filter { it.state == PictureState.GENERATING }

    /** 该消息用到的所有表情情绪词，供表情包库匹配。 */
    fun stickerEmotions(segments: List<Segment>): List<Segment.Sticker> =
        segments.filterIsInstance<Segment.Sticker>()
}

package com.kith.app.ai.engine

import com.kith.app.ai.ChatTurn
import com.kith.app.ai.LlmClient
import com.kith.app.ai.LlmEvent
import com.kith.app.ai.VisionBridge
import com.kith.app.ai.protocol.ChatProtocol
import com.kith.app.ai.prompt.Prompts
import com.kith.app.core.Ids
import com.kith.app.data.catalog.ModelCatalog
import com.kith.app.data.settings.SettingsSnapshot
import com.kith.app.data.store.SocietyBundle
import com.kith.app.domain.Character
import com.kith.app.domain.ChatMessage
import com.kith.app.domain.ChatThread
import com.kith.app.domain.LogEntry
import com.kith.app.domain.LogKind
import com.kith.app.domain.MsgRole
import com.kith.app.domain.MsgStatus
import com.kith.app.domain.Segment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Locale

/** 智能体产出的事件流。 */
sealed interface AgentEvent {
    data object Started : AgentEvent

    /** 增量原文。UI 侧用 ChatProtocol.parseStreaming 实时渲染。 */
    data class Delta(val raw: String) : AgentEvent

    /** 走了一次视觉桥接（提示用户：这个模型的图是转述来的）。 */
    data class VisionUsed(val description: String) : AgentEvent

    data class Done(
        val message: ChatMessage,
        val log: LogEntry,
        val costUsd: Double,
    ) : AgentEvent

    data class Failed(val error: String, val log: LogEntry) : AgentEvent
}

/**
 * 粗粒度 token 估算。
 *
 * 流式输出下部分厂商不返回 usage，总不能让花费统计永远是 0。中文与拉丁文的
 * 字符/词元比差得很远，所以分开算：中日韩字符约 1 字 ≈ 0.7 token，
 * 其余按 4 字符 ≈ 1 token。
 */
object Tokens {
    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        text.forEach { c ->
            if (c.code in 0x2E80..0x9FFF || c.code in 0xF900..0xFAFF || c.code in 0xFF00..0xFFEF) {
                cjk++
            } else {
                other++
            }
        }
        return (cjk * 0.7 + other / 4.0).toInt().coerceAtLeast(1)
    }
}

/**
 * 人物智能体 —— 一个角色在对话中的完整行为。
 *
 * 关键设计点：
 *
 * 1. **消息完全闭塞。** 每个角色一条独立会话（`chats/{charId}.json`），
 *    system prompt 里只出现这个角色自己的关系与记忆。A 说过的话绝不会漏到 B 的
 *    上下文里 —— 这是产品明确要求的「消息也是闭塞的不能串台」。
 *
 * 2. **看图能力自动补齐。** 用户发图时，如果角色所用模型支持视觉就直接多模态发送；
 *    如果是纯文本模型，先走 [VisionBridge] 转述，再把描述注入对话。对用户透明，
 *    换任何模型发图都有人接得住。
 *
 * 3. **人格由标签驱动。** 「恋人」「朋友」「家人」这些标签会写进 system prompt，
 *    直接改变模型的称呼、距离感和主动性，这就是「打标签然后按标签走」的落地。
 */
class CharacterAgent(
    private val llm: LlmClient,
    private val vision: VisionBridge,
    private val catalog: ModelCatalog,
) {

    fun reply(
        bundle: SocietyBundle,
        settings: SettingsSnapshot,
        /** 说话的角色 */
        speaker: Character,
        /** 用户（上帝视角下也是用户本人） */
        user: Character?,
        thread: ChatThread,
        userText: String,
        /** 用户发的图：本地路径或 URL */
        attachment: String = "",
    ): Flow<AgentEvent> = flow {
        val society = bundle.society

        // 1. 决定用哪个模型：角色自己的 → 社会默认 → 报错
        val ref = speaker.model ?: society.defaultCharacterModel
        val resolved = settings.resolve(ref)
            ?: run {
                val msg = "「${speaker.name}」还没有分配模型，也没有设置社会默认模型。" +
                    "请到人物详情或设置里指定。"
                emit(AgentEvent.Failed(msg, failLog(speaker, msg)))
                return@flow
            }
        val (endpoint, modelRef) = resolved

        emit(AgentEvent.Started)

        // 2. 处理图片
        val modelSupportsVision = catalog.find(modelRef.modelId)?.supportsVision
            // 目录里查不到（用户自定义模型）时保守假设不支持，宁可多走一次桥接
            ?: false

        var effectiveText = userText
        var imageForModel: List<String> = emptyList()

        if (attachment.isNotEmpty()) {
            if (modelSupportsVision) {
                imageForModel = listOf(attachment)
            } else if (settings.visionEnabled) {
                val described = vision.describe(settings, attachment, VisionBridge.CHAT_QUESTION)
                described.getOrNull()?.let { desc ->
                    emit(AgentEvent.VisionUsed(desc))
                    effectiveText = vision.asInjection(desc, user?.name ?: "对方") +
                        if (userText.isNotBlank()) "\n\n" + userText else ""
                }
            } else {
                // 既看不了图、桥接也关着 —— 明确告诉用户为什么，别让它假装没收到
                effectiveText = userText + "\n\n" +
                    "[对方发来了一张图片，但你当前的模型不支持看图，视觉桥接也未开启，" +
                    "所以你看不到这张图。请自然地表示你没看清，不要编造图片内容。]"
            }
        }

        // 3. 组装对话
        val system = Prompts.characterSystem(
            society = society,
            self = speaker,
            user = user,
            relations = bundle.relationsOf(speaker.id),
            allCharacters = bundle.characters,
            recent = emptyList(), // 历史以真实轮次给出，避免与 system 里的摘要重复
            stickerLibrary = bundle.stickers,
        )

        val history = thread.messages
            .filter { it.status == MsgStatus.DONE && it.role != MsgRole.SYSTEM }
            .takeLast(HISTORY_TURNS)

        val turns = buildList {
            add(ChatTurn.system(system))
            history.forEach { m ->
                when (m.role) {
                    // 用户消息必须带协议标签（表情/转账），否则「刚发了个红包」
                    // 在模型眼里就是一条空消息
                    MsgRole.USER -> add(ChatTurn.user(m.protocolText().ifEmpty { m.plainText() }))
                    MsgRole.CHARACTER -> add(ChatTurn.assistant(m.raw.ifEmpty { m.plainText() }))
                    MsgRole.NARRATOR -> add(ChatTurn.user("（旁白）${m.plainText()}"))
                    MsgRole.SYSTEM -> Unit
                }
            }
            add(ChatTurn.user(effectiveText, imageForModel))
        }

        // 4. 流式生成
        val sb = StringBuilder()
        var inTok = 0
        var outTok = 0
        var usageReported = false
        var failure: String? = null

        try {
            llm.streamChat(
                endpoint = endpoint,
                modelId = modelRef.modelId,
                turns = turns,
                temperature = 0.95,
                maxTokens = 1500,
            ).collect { ev ->
                when (ev) {
                    is LlmEvent.Delta -> {
                        sb.append(ev.text)
                        emit(AgentEvent.Delta(sb.toString()))
                    }
                    is LlmEvent.Usage -> {
                        inTok = ev.inputTokens
                        outTok = ev.outputTokens
                        usageReported = true
                    }
                    LlmEvent.Started, is LlmEvent.Finished -> Unit
                }
            }
        } catch (t: Throwable) {
            failure = t.message ?: t.javaClass.simpleName
        }

        val raw = sb.toString().trim()

        if (failure != null && raw.isEmpty()) {
            emit(AgentEvent.Failed(failure, failLog(speaker, failure, modelRef.display())))
            return@flow
        }

        // 渠道故障时中转站可能把字面 "null" 当正文吐回来。这种消息绝不能落库：
        // 一旦进了对话历史，模型会把 null 当正常回复学样，之后条条都是 null。
        if (raw.isEmpty() || raw.equals("null", ignoreCase = true)) {
            val hint = if (raw.isEmpty()) "空内容" else "空内容（null）"
            val msg = "「${speaker.name}」的模型返回了$hint —— 通常是该渠道临时故障，" +
                "请换个模型或稍后重试。"
            emit(AgentEvent.Failed(msg, failLog(speaker, msg, modelRef.display())))
            return@flow
        }

        // 5. 估算用量并计价
        val estimated = !usageReported
        if (estimated) {
            inTok = Tokens.estimate(turns.joinToString("") { it.content + it.images.size })
            outTok = Tokens.estimate(raw)
        }
        val cost = catalog.estimateCostUsd(modelRef.modelId, inTok, outTok)

        val segments = ChatProtocol.parse(raw)
        val message = ChatMessage(
            id = Ids.new("msg"),
            role = MsgRole.CHARACTER,
            charId = speaker.id,
            segments = segments,
            raw = raw,
            ts = System.currentTimeMillis(),
            status = if (failure == null) MsgStatus.DONE else MsgStatus.FAILED,
            modelLabel = modelRef.display(),
            tokensIn = inTok,
            tokensOut = outTok,
            costUsd = cost,
            error = failure.orEmpty(),
            attachment = attachment,
        )

        val log = LogEntry(
            id = Ids.new("log"),
            ts = message.ts,
            kind = LogKind.CHARACTER,
            actor = speaker.name,
            title = if (failure == null) "回复了消息" else "回复失败",
            detail = buildString {
                appendLine("模型：${modelRef.display()}")
                if (attachment.isNotEmpty()) {
                    appendLine(
                        if (modelSupportsVision) "收到图片：直接多模态识别"
                        else "收到图片：经视觉桥接转述",
                    )
                }
                appendLine("发送：${userText.ifEmpty { "（仅图片）" }}")
                appendLine("回复：$raw")
                if (estimated) append("（token 为估算值，该厂商未返回用量）")
            },
            modelLabel = modelRef.display(),
            tokensIn = inTok,
            tokensOut = outTok,
            costUsd = cost,
            refMsgId = message.id,
        )

        if (failure != null) {
            emit(AgentEvent.Failed(failure, log))
        } else {
            emit(AgentEvent.Done(message, log, cost))
        }
    }

    /**
     * 上帝视角：让两个 NPC 之间对话。
     * 与用户对话共用同一套人格逻辑，只是「对方」从用户换成了另一个角色。
     */
    fun dialogue(
        bundle: SocietyBundle,
        settings: SettingsSnapshot,
        speaker: Character,
        listener: Character,
        topic: String,
    ): Flow<AgentEvent> {
        val opening = buildString {
            appendLine("（你现在在和「${listener.name}」说话，不是在和用户说话。）")
            appendLine("对方与你的关系：")
            bundle.relationsOf(speaker.id)
                .firstOrNull { it.fromId == listener.id || it.toId == listener.id }
                ?.let { appendLine("  ${it.label}${if (it.note.isNotEmpty()) "（${it.note}）" else ""}") }
                ?: appendLine("  尚无明确关系，按初次接触处理。")
            appendLine()
            appendLine("话题：$topic")
        }
        // 用 listener 作为「对方」，复用同一套处理
        return reply(
            bundle = bundle,
            settings = settings,
            speaker = speaker,
            user = listener,
            thread = ChatThread(speaker.id),
            userText = opening,
        )
    }

    private fun failLog(speaker: Character, error: String, modelLabel: String = ""): LogEntry =
        LogEntry(
            id = Ids.new("log"),
            ts = System.currentTimeMillis(),
            kind = LogKind.CHARACTER,
            actor = speaker.name,
            title = "回复失败",
            detail = error,
            modelLabel = modelLabel,
        )

    private companion object {
        /** 带进上下文的历史轮数。太大会显著推高成本且降低人格稳定性。 */
        const val HISTORY_TURNS = 24
    }
}

/**
 * 金额展示 —— 全局以**人民币**为准。
 *
 * 一个容易搞错的点：**入参统一是美元，出参才是人民币**。
 * 因为模型目录的价格源（OpenRouter）按美元计价，记账如果中途换成人民币，
 * 汇率一变历史花费就跟着变 —— 那是「事实」而不是「口径」。
 * 所以内部一律用美元记账，只在显示这一层按当前汇率换算。
 *
 * 汇率由 [rateProvider] 提供，AppGraph 在启动时把它接到设置上，
 * 用户在设置里改了汇率立刻全局生效。
 */
object Money {

    /** 兜底汇率。真实值来自设置，可在设置页改成自己看到的价格。 */
    const val DEFAULT_USD_TO_CNY = 7.2

    var rateProvider: () -> Double = { DEFAULT_USD_TO_CNY }

    /** 当前生效的美元兑人民币汇率。 */
    val usdToCny: Double
        get() = rateProvider().let { if (it.isFinite() && it > 0.0) it else DEFAULT_USD_TO_CNY }

    /** 美元金额 → 人民币金额（纯数值，不带符号）。 */
    fun toCny(usdValue: Double): Double = usdValue * usdToCny

    /**
     * 主展示函数：给一个**美元**金额，返回**人民币**字符串。
     *
     * 名字刻意叫 `cny` 而不是 `usd`，就是为了让「参数是美元、结果是人民币」
     * 这件事在调用点上肉眼可见，避免有人误把人民币金额再传进来。
     */
    fun cny(usdValue: Double): String {
        val v = toCny(usdValue)
        return when {
            v <= 0.0 -> "¥0"
            v < 0.01 -> "<¥0.01"
            v < 10.0 -> "¥" + String.format(Locale.CHINA, "%.3f", v)
            v < 1000.0 -> "¥" + String.format(Locale.CHINA, "%.2f", v)
            else -> "¥" + String.format(Locale.CHINA, "%.0f", v)
        }
    }

    /** 目录里的单价（美元/百万 token）→ 人民币展示串。 */
    fun perMillion(promptUsd: Double?, completionUsd: Double?): String {
        if (promptUsd == null && completionUsd == null) return "价格未提供"
        val p = promptUsd?.let { "¥" + trim(it) } ?: "?"
        val c = completionUsd?.let { "¥" + trim(it) } ?: "?"
        return "输入 $p / 输出 $c 每百万 token"
    }

    /** 人民币数值的紧凑写法（自己带符号的地方用）。 */
    private fun trim(usdValue: Double): String {
        val v = toCny(usdValue)
        return when {
            v == 0.0 -> "0"
            v < 0.01 -> String.format(Locale.CHINA, "%.4f", v)
            v < 1.0 -> String.format(Locale.CHINA, "%.3f", v)
            v < 100.0 -> String.format(Locale.CHINA, "%.2f", v)
            else -> String.format(Locale.CHINA, "%.0f", v)
        }
    }

    /** Token 数量的展示（与货币无关）。 */
    fun tokenCount(v: Int): String = when {
        v >= 1_000_000 -> String.format(Locale.US, "%.1fM", v / 1_000_000.0)
        v >= 1_000 -> String.format(Locale.US, "%.1fk", v / 1_000.0)
        else -> v.toString()
    }
}

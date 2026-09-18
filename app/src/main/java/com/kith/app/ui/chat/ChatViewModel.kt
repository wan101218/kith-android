package com.kith.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kith.app.ai.engine.AgentEvent
import com.kith.app.ai.engine.Money
import com.kith.app.ai.engine.Tokens
import com.kith.app.ai.protocol.ChatProtocol
import com.kith.app.core.Ids
import com.kith.app.data.store.SocietyBundle
import com.kith.app.domain.ChatMessage
import com.kith.app.domain.ChatThread
import com.kith.app.domain.LogEntry
import com.kith.app.domain.LogKind
import com.kith.app.domain.MsgRole
import com.kith.app.domain.MsgStatus
import com.kith.app.domain.PictureState
import com.kith.app.domain.Segment
import com.kith.app.domain.Sticker
import com.kith.app.data.sticker.StickerResolution
import com.kith.app.kithGraph
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatUiState(
    val loading: Boolean = true,
    val bundle: SocietyBundle? = null,
    val partner: com.kith.app.domain.Character? = null,
    val messages: List<ChatMessage> = emptyList(),
    /** 正在流式生成的那条消息（尚未落盘） */
    val streaming: ChatMessage? = null,
    val generating: Boolean = false,
    val stickerLibrary: List<Sticker> = emptyList(),
    val notice: String = "",
    val sessionCostUsd: Double = 0.0,
)

/**
 * 单个角色会话的 ViewModel。
 *
 * 每个角色一条独立会话文件，消息完全闭塞 —— system prompt 里只出现这个角色
 * 自己的关系与记忆，A 说过的话不会漏进 B 的上下文。这是产品明确要求的
 * 「消息也是闭塞的不能串台」。
 */
class ChatViewModel(
    private val societyId: String,
    private val charId: String,
) : ViewModel() {

    private val graph = kithGraph

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var streamJob: Job? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val bundle = graph.store.load(societyId)
            var messages = graph.store.loadThread(societyId, charId).messages

            // 空会话 + 角色有开场白 → 用开场白当第一条消息。
            // 开场白是人物卡上独立设计的字段（与人设长度无关），
            // 比让模型从零生成第一印象更可控，也更贴用户配置的角色。
            if (messages.isEmpty()) {
                bundle?.character(charId)?.let { partner ->
                    if (partner.openingLine.isNotBlank()) {
                        messages = listOf(
                            ChatMessage(
                                id = Ids.new("msg"),
                                role = MsgRole.CHARACTER,
                                charId = partner.id,
                                segments = listOf(Segment.Text(partner.openingLine)),
                                raw = partner.openingLine,
                                ts = (partner.createdAt.takeIf { it > 0 }
                                    ?: System.currentTimeMillis()),
                                modelLabel = "开场白",
                            ),
                        )
                        graph.store.saveThread(
                            societyId,
                            ChatThread(charId, messages),
                        )
                    }
                }
            }

            _state.value = _state.value.copy(
                loading = false,
                bundle = bundle,
                partner = bundle?.character(charId),
                messages = messages,
                stickerLibrary = bundle?.stickers.orEmpty(),
                sessionCostUsd = messages.sumOf { it.costUsd },
            )
        }
    }

    fun clearNotice() {
        _state.value = _state.value.copy(notice = "")
    }

    /** 发一条消息并让角色回应。 */
    fun send(text: String, attachment: String = "") {
        val bundle = _state.value.bundle ?: return
        val partner = _state.value.partner ?: return
        if (text.isBlank() && attachment.isEmpty()) return
        if (streamJob?.isActive == true) return

        streamJob = viewModelScope.launch {
            // 1. 先把用户这条落盘。
            // 用户文本里带协议标签（从表情面板/转账框发出）时解析成对应片段，
            // 气泡里渲染的就是表情/转账卡片而不是标签原文。
            val hasProtocol = text.contains("<sticker") || text.contains("<transfer")
            val userMsg = ChatMessage(
                id = Ids.new("msg"),
                role = MsgRole.USER,
                segments = when {
                    text.isBlank() -> emptyList()
                    hasProtocol -> ChatProtocol.parse(text)
                    else -> listOf(Segment.Text(text))
                },
                raw = text,
                ts = System.currentTimeMillis(),
                status = MsgStatus.DONE,
                attachment = attachment,
            )
            val withUser = _state.value.messages + userMsg
            _state.value = _state.value.copy(messages = withUser, generating = true, notice = "")
            graph.store.saveThread(
                societyId,
                com.kith.app.domain.ChatThread(charId, withUser, userMsg.ts),
            )
            graph.store.appendLog(
                societyId,
                LogEntry(
                    id = Ids.new("log"),
                    ts = userMsg.ts,
                    kind = LogKind.USER,
                    actor = bundle.userCharacter?.name ?: "你",
                    title = "发了一条消息",
                    detail = buildString {
                        if (attachment.isNotEmpty()) appendLine("附带一张图片")
                        if (text.isNotBlank()) {
                            append(
                                when {
                                    userMsg.segments.size == 1 && userMsg.segments.first() is Segment.Sticker ->
                                        "发了个表情：${userMsg.segments.filterIsInstance<Segment.Sticker>().first().emotion}"
                                    userMsg.segments.size == 1 && userMsg.segments.first() is Segment.Transfer -> {
                                        val t = userMsg.segments.filterIsInstance<Segment.Transfer>().first()
                                        "转了账：¥%.2f".format(t.amount) +
                                            if (t.note.isNotEmpty()) "（${t.note}）" else ""
                                    }
                                    else -> "内容：$text"
                                },
                            )
                        }
                    },
                    refMsgId = userMsg.id,
                ),
            )

            // 2. 流式生成回复
            val thread = com.kith.app.domain.ChatThread(charId, withUser)
            var rawSoFar = ""

            graph.agent.reply(
                bundle = bundle,
                settings = graph.settings.current,
                speaker = partner,
                user = bundle.userCharacter,
                thread = thread,
                userText = text,
                attachment = attachment,
            ).collect { ev ->
                when (ev) {
                    is AgentEvent.Started -> {
                        _state.value = _state.value.copy(
                            streaming = ChatMessage(
                                id = "streaming",
                                role = MsgRole.CHARACTER,
                                charId = partner.id,
                                ts = System.currentTimeMillis(),
                                status = MsgStatus.STREAMING,
                            ),
                        )
                    }

                    is AgentEvent.Delta -> {
                        rawSoFar = ev.raw
                        _state.value = _state.value.copy(
                            streaming = ChatMessage(
                                id = "streaming",
                                role = MsgRole.CHARACTER,
                                charId = partner.id,
                                // 流式期间用截断保护版解析，避免半截标签闪成文本
                                segments = ChatProtocol.parseStreaming(ev.raw),
                                raw = ev.raw,
                                ts = System.currentTimeMillis(),
                                status = MsgStatus.STREAMING,
                            ),
                        )
                    }

                    is AgentEvent.VisionUsed -> {
                        graph.store.appendLog(
                            societyId,
                            LogEntry(
                                id = Ids.new("log"),
                                ts = System.currentTimeMillis(),
                                kind = LogKind.VISION,
                                actor = partner.name,
                                title = "用视觉桥接看了你发的图",
                                detail = ev.description,
                                modelLabel = graph.settings.current.visionModel,
                            ),
                        )
                    }

                    is AgentEvent.Done -> {
                        finalize(ev.message, ev.log)
                        maybeGeneratePictures(ev.message)
                    }

                    is AgentEvent.Failed -> {
                        graph.store.appendLog(societyId, ev.log)
                        _state.value = _state.value.copy(
                            streaming = null,
                            generating = false,
                            notice = ev.error,
                        )
                        // 把失败的尝试也留一条痕迹，方便用户看到发生了什么
                        val failed = ChatMessage(
                            id = Ids.new("msg"),
                            role = MsgRole.SYSTEM,
                            segments = listOf(Segment.Text("回复失败：${ev.error}")),
                            raw = "",
                            ts = System.currentTimeMillis(),
                            status = MsgStatus.FAILED,
                        )
                        val list = _state.value.messages + failed
                        _state.value = _state.value.copy(messages = list)
                        graph.store.saveThread(societyId, com.kith.app.domain.ChatThread(charId, list))
                    }
                }
            }
        }
    }

    /** 用户从表情面板发一个表情（库内图片表情带 id，系统情绪词走 emotion）。 */
    fun sendSticker(emotion: String, stickerId: String = "") {
        if (emotion.isBlank() && stickerId.isBlank()) return
        val tag = buildString {
            append("<sticker")
            if (stickerId.isNotBlank()) append(" id=\"$stickerId\"")
            if (emotion.isNotBlank()) append(" emotion=\"$emotion\"")
            append(" />")
        }
        send(tag)
    }

    /** 用户发起一笔转账（模拟支付，不接真实通道），raw 走协议标签让 AI 感知。 */
    fun sendTransfer(amount: Double, note: String) {
        if (amount <= 0.0) return
        val partner = _state.value.partner
        val tag = buildString {
            append("<transfer amount=\"")
            append(java.util.Locale.US, "%.2f", amount)
            append("\" to=\"${partner?.name ?: ""}\"")
            if (note.isNotBlank()) append(" note=\"${note.trim()}\"")
            append(" />")
        }
        send(tag)
    }

    private suspend fun finalize(message: ChatMessage, log: LogEntry) {
        val list = _state.value.messages + message
        _state.value = _state.value.copy(
            messages = list,
            streaming = null,
            generating = false,
            sessionCostUsd = _state.value.sessionCostUsd + message.costUsd,
        )
        graph.store.saveThread(societyId, com.kith.app.domain.ChatThread(charId, list))
        graph.store.appendLog(societyId, log)
        refreshStickers()
    }

    /** 消息里的 `<chat_image prompt="...">` 要异步补图。 */
    private suspend fun maybeGeneratePictures(message: ChatMessage) {
        val pending = ChatProtocol.picturesToGenerate(message.segments)
        if (pending.isEmpty()) return

        val bundle = _state.value.bundle ?: return
        val imageRef = bundle.society.imageModel
        val resolved = graph.settings.current.resolve(imageRef)
        if (resolved == null) {
            updateMessage(message.id) { m ->
                m.copy(
                    segments = m.segments.map { seg ->
                        if (seg is Segment.Picture && seg.state == PictureState.GENERATING) {
                            seg.copy(state = PictureState.FAILED, caption = seg.caption.ifEmpty { "未配置文生图模型" })
                        } else seg
                    },
                )
            }
            return
        }
        val (endpoint, ref) = resolved
        val style = pending.firstOrNull()?.style.orEmpty()
        val artStyle = style.ifEmpty { bundle.society.tropes.firstOrNull()?.let { "$it 风格插画" } ?: "日系清新插画" }

        pending.forEach { pic ->
            val prompt = pic.prompt.ifEmpty { pic.searchQuery }
            val result = graph.imageGen.generate(
                societyId = societyId,
                endpoint = endpoint,
                modelId = ref.modelId,
                prompt = prompt,
                ratio = pic.ratio.ifEmpty { "1:1" },
                style = artStyle,
            )
            result.onSuccess { img ->
                updateMessage(message.id) { m ->
                    m.copy(
                        segments = m.segments.map { seg ->
                            if (seg is Segment.Picture && seg.prompt == pic.prompt && seg.state == PictureState.GENERATING) {
                                seg.copy(url = img.best(), state = PictureState.READY)
                            } else seg
                        },
                    )
                }
                graph.store.appendLog(
                    societyId,
                    LogEntry(
                        id = Ids.new("log"),
                        ts = System.currentTimeMillis(),
                        kind = LogKind.IMAGE,
                        actor = graph.settings.current.let { _ -> _state.value.partner?.name ?: "角色" },
                        title = "生成了一张图片",
                        detail = "提示词：$prompt\n模型：${ref.display()}",
                        modelLabel = ref.display(),
                        refMsgId = message.id,
                    ),
                )
            }.onFailure { e ->
                updateMessage(message.id) { m ->
                    m.copy(
                        segments = m.segments.map { seg ->
                            if (seg is Segment.Picture && seg.prompt == pic.prompt) {
                                seg.copy(state = PictureState.FAILED, caption = e.message.orEmpty())
                            } else seg
                        },
                    )
                }
            }
        }
    }

    private suspend fun updateMessage(msgId: String, transform: (ChatMessage) -> ChatMessage) {
        val list = _state.value.messages.map { if (it.id == msgId) transform(it) else it }
        _state.value = _state.value.copy(messages = list)
        graph.store.saveThread(societyId, com.kith.app.domain.ChatThread(charId, list))
    }

    /** 用户点开转账卡片并确认（模拟支付，不做任何真实扣款）。 */
    fun confirmTransfer(msgId: String, transfer: Segment.Transfer) {
        viewModelScope.launch {
            updateMessage(msgId) { m ->
                m.copy(
                    segments = m.segments.map { seg ->
                        if (seg is Segment.Transfer && seg.amount == transfer.amount) {
                            seg.copy(confirmed = true)
                        } else seg
                    },
                )
            }
            graph.store.appendLog(
                societyId,
                LogEntry(
                    id = Ids.new("log"),
                    ts = System.currentTimeMillis(),
                    kind = LogKind.USER,
                    actor = _state.value.bundle?.userCharacter?.name ?: "你",
                    title = "收下了一笔转账",
                    detail = "金额 ¥%.2f".format(transfer.amount) +
                        if (transfer.note.isNotEmpty()) "，备注：${transfer.note}" else "",
                    refMsgId = msgId,
                ),
            )
        }
    }

    /** 表情包命中后累加使用次数，让高频表情优先被选中。 */
    private suspend fun refreshStickers() {
        val b = _state.value.bundle ?: return
        val last = _state.value.messages.lastOrNull() ?: return
        var lib = b.stickers
        var changed = false
        last.segments.filterIsInstance<Segment.Sticker>().forEach { st ->
            val res = graph.stickers.resolve(lib, st.emotion, st.stickerId)
            if (res is StickerResolution.Image) {
                lib = graph.stickers.bumpUsage(lib, res.sticker.id)
                changed = true
            }
        }
        if (changed) {
            graph.store.saveStickers(societyId, lib)
            _state.value = _state.value.copy(stickerLibrary = lib)
        }
    }

    /** 解析一条表情标签，供 UI 渲染。 */
    fun resolveSticker(tag: Segment.Sticker): StickerResolution =
        graph.stickers.resolve(_state.value.stickerLibrary, tag.emotion, tag.stickerId)

    override fun onCleared() {
        streamJob?.cancel()
        super.onCleared()
    }

    companion object {
        fun factory(societyId: String, charId: String) = viewModelFactory {
            initializer { ChatViewModel(societyId, charId) }
        }
    }
}

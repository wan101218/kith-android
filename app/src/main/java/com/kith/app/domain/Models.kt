package com.kith.app.domain

/**
 * Kith 领域模型。
 *
 * 设计约束（来自产品需求）：
 *  1. 每个社会 = 一个独立项目，之间完全隔离，各自一个文件夹 —— 因此所有实体都挂在
 *     `societyId` 之下，且 id 全局唯一，便于导出/导入时避免冲突。
 *  2. 配置好的接入点与模型可以被复用 —— 因此 `AiEndpoint` 与 `ModelRef` 分离，
 *     ModelRef 只存引用与展示快照，不存密钥。
 *  3. 人物有「重要性」并且决定用哪个档位的模型 —— `Importance` 直接携带默认档位。
 */

// ── 枚举 ────────────────────────────────────────────────────────────────────

enum class Gender(val label: String) {
    MALE("男"),
    FEMALE("女"),
    OTHER("其他"),
    UNKNOWN("未设定");

    companion object {
        fun of(raw: String?): Gender =
            entries.firstOrNull { it.name == raw } ?: UNKNOWN
    }
}

/** 模型档位。旁白按人物重要性自动分配。 */
enum class ModelTier(val label: String, val hint: String) {
    PREMIUM("旗舰", "核心人物、关键剧情节点使用，成本最高"),
    STANDARD("标准", "重要人物与常规剧情推进使用"),
    ECONOMY("经济", "配角、路人、批量生成场景使用，成本最低");

    companion object {
        fun of(raw: String?): ModelTier = entries.firstOrNull { it.name == raw } ?: STANDARD
    }
}

/**
 * 人物重要性。除决定模型档位外，还决定关系图上的渲染方式与点击行为：
 * LEAD/MAJOR 展示资料 + 可发消息；SUPPORTING 及以下只展示资料。
 */
enum class Importance(val label: String, val hint: String, val tier: ModelTier, val chatEnabled: Boolean) {
    LEAD("核心", "故事的支柱，剧情围绕 TA 展开", ModelTier.PREMIUM, true),
    MAJOR("重要", "有独立戏份与关系网", ModelTier.STANDARD, true),
    SUPPORTING("配角", "参与剧情但有明确功能位", ModelTier.ECONOMY, true),
    MINOR("次要", "背景人物，少量戏份", ModelTier.ECONOMY, false),
    EXTRA("路人", "一次性出场，无需记忆", ModelTier.ECONOMY, false);

    companion object {
        fun of(raw: String?): Importance = entries.firstOrNull { it.name == raw } ?: SUPPORTING
        fun ofScore(score: Int): Importance = when {
            score >= 85 -> LEAD
            score >= 65 -> MAJOR
            score >= 45 -> SUPPORTING
            score >= 25 -> MINOR
            else -> EXTRA
        }
    }
}

/** 剧情走向模板，覆盖产品明确要求的 BL / GL / BG / BZ 四类。 */
enum class PlotOrientation(val code: String, val label: String, val hint: String) {
    BG("bg", "异性向", "男女情感线为主轴"),
    BL("bl", "耽美向", "男性之间情感线为主轴"),
    GL("gl", "百合向", "女性之间情感线为主轴"),
    BZ("bz", "无 CP", "以群像、事业、悬疑等非情感线为主轴"),
    CUSTOM("custom", "自定义", "完全由你定义走向");

    companion object {
        fun of(raw: String?): PlotOrientation = entries.firstOrNull { it.name == raw } ?: BG
        fun byCode(code: String?): PlotOrientation =
            entries.firstOrNull { it.code == code } ?: BG
    }
}

/** 关系类型。symmetric 表示是否双向等同（「恋人」是对称的，「上司→下属」不是）。 */
enum class RelationKind(val label: String, val symmetric: Boolean) {
    FAMILY("亲属", true),
    LOVER("恋人", true),
    SPOUSE("伴侣", true),
    FRIEND("朋友", true),
    BEST_FRIEND("挚友", true),
    RIVAL("对手", true),
    ENEMY("宿敌", true),
    COLLEAGUE("同事", true),
    CLASSMATE("同学", true),
    SUPERIOR("上司", false),
    SUBORDINATE("下属", false),
    MENTOR("师长", false),
    STUDENT("学生", false),
    NEIGHBOR("邻里", true),
    ACQUAINTANCE("点头之交", true),
    CUSTOM("自定义", true);

    companion object {
        fun of(raw: String?): RelationKind = entries.firstOrNull { it.name == raw } ?: ACQUAINTANCE
    }
}

enum class MsgRole(val label: String) {
    USER("你"),
    CHARACTER("角色"),
    NARRATOR("旁白"),
    SYSTEM("系统");

    companion object {
        fun of(raw: String?): MsgRole = entries.firstOrNull { it.name == raw } ?: CHARACTER
    }
}

enum class MsgStatus { PENDING, STREAMING, DONE, FAILED }

enum class LogKind(val label: String) {
    NARRATOR("旁白"),
    CHARACTER("角色"),
    USER("用户"),
    VISION("视觉"),
    IMAGE("生图"),
    MODEL("模型"),
    SYSTEM("系统");

    companion object {
        fun of(raw: String?): LogKind = entries.firstOrNull { it.name == raw } ?: SYSTEM
    }
}

// ── 模型接入 ────────────────────────────────────────────────────────────────

/** 接入点类型 —— LLM 走 chat/completions，生图走 images/generations。 */
enum class EndpointKind(val label: String) {
    LLM("对话模型"),
    IMAGE("文生图模型");

    companion object {
        fun of(raw: String?): EndpointKind = entries.firstOrNull { it.name == raw } ?: LLM
    }
}

/**
 * 用户配置的一个接入点。baseUrl + apiKey 组合会被保存下来重复使用，
 * 满足「配置好的 AI 后续可以直接使用」。
 */
data class AiEndpoint(
    val id: String,
    val label: String,
    val vendor: String,
    val kind: EndpointKind = EndpointKind.LLM,
    val baseUrl: String,
    val apiKey: String = "",
    val createdAt: Long = 0L,
)

/** 对一个具体模型的引用。只存引用与展示快照，不存密钥。 */
data class ModelRef(
    val endpointId: String,
    val modelId: String,
    val label: String = "",
    val vendor: String = "",
) {
    /** 价格等运行时信息由 ModelCatalog 提供，这里只给展示名。 */
    fun display(): String = label.ifEmpty { modelId }
}

// ── 社会 ────────────────────────────────────────────────────────────────────

data class Society(
    val id: String,
    val name: String,
    /** 世界观描述 */
    val worldSetting: String = "",
    val orientation: PlotOrientation = PlotOrientation.BG,
    /** 剧情走向描述 */
    val plotDirection: String = "",
    /** 题材/特殊人群标签，如「强强」「救赎」「群像」 */
    val tropes: List<String> = emptyList(),
    val coverSeed: Int = 0,
    /**
     * 自定义封面图，值为该社会 media/ 目录下的**文件名**（不是绝对路径 ——
     * 导入存档会分配新社会 id，绝对路径会失效）。
     * 空字符串 = 使用 coverSeed 派生的程序化渐变封面。
     */
    val coverImage: String = "",
    /** 旁白 AI 使用哪个模型 */
    val narratorModel: ModelRef? = null,
    /** 新建人物的默认模型 */
    val defaultCharacterModel: ModelRef? = null,
    /** 文生图模型，用于生成头像与聊天配图 */
    val imageModel: ModelRef? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val archived: Boolean = false,
    val version: Int = ARCHIVE_VERSION,
) {
    companion object {
        const val ARCHIVE_VERSION = 1
    }
}

// ── 人物 ────────────────────────────────────────────────────────────────────

/**
 * 人物标签 —— 决定 TA 如何对待你。
 * 例如给主角 A 打上「恋人」，TA 后续与你和与其他 NPC 沟通时都会按恋人标准行事。
 */
data class CharacterTag(
    val label: String,
    val hint: String = "",
    val builtin: Boolean = true,
)

data class Character(
    val id: String,
    val name: String,
    val alias: String = "",
    val gender: Gender = Gender.UNKNOWN,
    val age: String = "",
    /** 一句话简介 */
    val oneLiner: String = "",
    /** 性格特点 */
    val personality: String = "",
    /** 背景故事 */
    val background: String = "",
    val appearance: String = "",
    /** 说话风格，会注入人格 prompt */
    val speechStyle: String = "",
    /**
     * 开场白 —— 这个角色对用户说的第一句话。
     * 独立于人设文本（两者长度本就不相关），空会话时用它当第一条消息，
     * 比让模型从零生成第一印象更可控。
     */
    val openingLine: String = "",
    /** 头像图（本地路径或远程 URL），可由文生图产出 */
    val avatarUrl: String = "",
    val importance: Importance = Importance.SUPPORTING,
    /** 该人物实际使用的模型；为空则回落到社会的默认人物模型 */
    val model: ModelRef? = null,
    /** 与「用户」的关系标签 */
    val tags: List<CharacterTag> = emptyList(),
    /** 是否为用户本人扮演的主角 */
    val isUser: Boolean = false,
    /** 是否由旁白 AI 自动生成 */
    val isGenerated: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    val displayName: String get() = if (alias.isNotEmpty()) "$name（$alias）" else name

    /** 标签拼成给 prompt 用的短语，如「恋人、同事」。 */
    fun tagPhrase(): String =
        tags.joinToString("、") { it.label }.ifEmpty { "普通相识" }
}

// ── 关系 ────────────────────────────────────────────────────────────────────

data class Relation(
    val id: String,
    val fromId: String,
    val toId: String,
    val kind: RelationKind = RelationKind.ACQUAINTANCE,
    val customLabel: String = "",
    /** 关系强度 0..100，影响关系图连线粗细与 prompt 中的亲密程度 */
    val intensity: Int = 50,
    val note: String = "",
    val createdAt: Long = 0L,
) {
    val label: String get() = customLabel.ifEmpty { kind.label }
}

// ── 消息 ────────────────────────────────────────────────────────────────────

/**
 * 一条消息被 Human Chat Protocol 解析后的片段。
 * 对应 protocol-spec.md：`<chat_image>` / `<sticker>` / `<transfer>` 三类标签。
 */
sealed interface Segment {
    data class Text(val content: String) : Segment

    data class Sticker(
        val emotion: String = "",
        val stickerId: String = "",
    ) : Segment

    data class Picture(
        val url: String = "",
        val prompt: String = "",
        val searchQuery: String = "",
        val style: String = "",
        val ratio: String = "",
        val caption: String = "",
        /** 生图异步状态：生成中 / 成功 / 失败 */
        val state: PictureState = PictureState.READY,
    ) : Segment

    data class Transfer(
        val amount: Double = 0.0,
        val to: String = "",
        val note: String = "",
        /** 用户是否已点开卡片并确认（模拟支付） */
        val confirmed: Boolean = false,
    ) : Segment
}

enum class PictureState { READY, GENERATING, FAILED }

data class ChatMessage(
    val id: String,
    val role: MsgRole,
    /** role == CHARACTER 时指向说话人 */
    val charId: String? = null,
    val segments: List<Segment> = emptyList(),
    /** 模型原始输出，保留以便重新解析与排查 */
    val raw: String = "",
    val ts: Long = 0L,
    val status: MsgStatus = MsgStatus.DONE,
    val modelLabel: String = "",
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
    val costUsd: Double = 0.0,
    val error: String = "",
    /** 用户发的原图（本地路径），用于纯文本模型走视觉桥接 */
    val attachment: String = "",
) {
    fun plainText(): String =
        segments.filterIsInstance<Segment.Text>().joinToString("") { it.content }

    /**
     * 还原成带协议标签的纯文本 —— 这是**给模型看**的形态。
     *
     * [plainText] 会丢掉表情/转账片段，导致「用户刚发了个红包」在 AI 眼里
     * 变成一条空消息。历史轮次序列化必须用这个方法，AI 才能感知并回应
     * 用户的表情与转账。
     */
    fun protocolText(): String = buildString {
        segments.forEach { seg ->
            when (seg) {
                is Segment.Text -> append(seg.content)
                is Segment.Sticker -> {
                    append("<sticker")
                    if (seg.stickerId.isNotEmpty()) append(" id=\"${seg.stickerId}\"")
                    if (seg.emotion.isNotEmpty()) append(" emotion=\"${seg.emotion}\"")
                    append(" />")
                }
                is Segment.Transfer -> {
                    append("<transfer amount=\"")
                    append(java.util.Locale.US, "%.2f", seg.amount)
                    append("\" to=\"${seg.to}\"")
                    if (seg.note.isNotEmpty()) append(" note=\"${seg.note}\"")
                    append(" />")
                }
                is Segment.Picture -> Unit // 图片走 attachment/视觉桥接通道，不进文本
            }
        }
    }
}

/** 一个角色与用户的独立会话。消息完全闭塞，不与其他角色串台。 */
data class ChatThread(
    val charId: String,
    val messages: List<ChatMessage> = emptyList(),
    val updatedAt: Long = 0L,
)

// ── 剧情 ────────────────────────────────────────────────────────────────────

data class PlotBeat(
    val ts: Long,
    val title: String,
    val detail: String = "",
    val actorId: String? = null,
    /** 是否由旁白自动推进产生 */
    val automatic: Boolean = false,
)

data class PlotState(
    val act: Int = 1,
    val title: String = "序章",
    val summary: String = "",
    val mood: String = "",
    /** 已埋下但尚未回收的伏笔 */
    val hooks: List<String> = emptyList(),
    val beats: List<PlotBeat> = emptyList(),
    val updatedAt: Long = 0L,
)

// ── 日志 ────────────────────────────────────────────────────────────────────

/**
 * 全量日志。旁白与所有模型的所作所为、说了什么，都必须留痕。
 */
data class LogEntry(
    val id: String,
    val ts: Long,
    val kind: LogKind,
    /** 行为主体：旁白 / 角色名 / 系统 */
    val actor: String,
    val title: String,
    val detail: String = "",
    val modelLabel: String = "",
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
    val costUsd: Double = 0.0,
    /** 关联的消息 id，便于从日志跳回对话 */
    val refMsgId: String? = null,
)

// ── 表情包 ──────────────────────────────────────────────────────────────────

/** 对应 protocol-spec.md 的表情包表结构。 */
data class Sticker(
    val id: String,
    val name: String,
    /** 本地路径或远程 URL */
    val imageUrl: String,
    /** 情绪标签数组，一个表情可对应多个词 */
    val emotions: List<String> = emptyList(),
    val source: String = SOURCE_SYSTEM,
    val usageCount: Int = 0,
    val createdAt: Long = 0L,
) {
    companion object {
        const val SOURCE_SYSTEM = "system_default"
        const val SOURCE_USER = "user_upload"
        const val SOURCE_GENERATED = "ai_generated"
    }
}

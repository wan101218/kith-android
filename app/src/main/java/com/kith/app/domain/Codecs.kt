package com.kith.app.domain

import com.kith.app.core.arr
import com.kith.app.core.b
import com.kith.app.core.d
import com.kith.app.core.i
import com.kith.app.core.jObj
import com.kith.app.core.l
import com.kith.app.core.obj
import com.kith.app.core.s
import com.kith.app.core.so
import com.kith.app.core.strList
import org.json.JSONArray
import org.json.JSONObject

/**
 * 领域模型 ↔ JSON 编解码。
 *
 * 全部手写而非依赖反射或序列化框架，原因：
 *  - 存档要能被用户导出成可读、可手改、可长期兼容的 JSON；
 *  - 导入别人分享的社会时字段可能缺失或多余，手写解析可以逐字段兜底，
 *    做到「缺字段不崩、多字段忽略」。
 */

// ── 基础 ────────────────────────────────────────────────────────────────────

fun ModelRef.toJson(): JSONObject = jObj(
    "endpointId" to endpointId,
    "modelId" to modelId,
    "label" to label,
    "vendor" to vendor,
)

fun JSONObject.toModelRef(): ModelRef = ModelRef(
    endpointId = s("endpointId"),
    modelId = s("modelId"),
    label = s("label"),
    vendor = s("vendor"),
)

fun AiEndpoint.toJson(): JSONObject = jObj(
    "id" to id,
    "label" to label,
    "vendor" to vendor,
    "kind" to kind.name,
    "baseUrl" to baseUrl,
    "apiKey" to apiKey,
    "createdAt" to createdAt,
)

fun JSONObject.toAiEndpoint(): AiEndpoint = AiEndpoint(
    id = s("id"),
    label = s("label"),
    vendor = s("vendor"),
    kind = EndpointKind.of(s("kind")),
    baseUrl = s("baseUrl"),
    apiKey = s("apiKey"),
    createdAt = l("createdAt"),
)

fun CharacterTag.toJson(): JSONObject = jObj(
    "label" to label,
    "hint" to hint,
    "builtin" to builtin,
)

fun JSONObject.toCharacterTag(): CharacterTag = CharacterTag(
    label = s("label"),
    hint = s("hint"),
    builtin = b("builtin", true),
)

// ── 社会 ────────────────────────────────────────────────────────────────────

fun Society.toJson(): JSONObject = jObj(
    "id" to id,
    "name" to name,
    "worldSetting" to worldSetting,
    "orientation" to orientation.name,
    "plotDirection" to plotDirection,
    "tropes" to tropes,
    "coverSeed" to coverSeed,
    "coverImage" to coverImage,
    "narratorModel" to narratorModel?.toJson(),
    "defaultCharacterModel" to defaultCharacterModel?.toJson(),
    "imageModel" to imageModel?.toJson(),
    "createdAt" to createdAt,
    "updatedAt" to updatedAt,
    "archived" to archived,
    "version" to version,
)

fun JSONObject.toSociety(): Society = Society(
    id = s("id"),
    name = s("name", "未命名社会"),
    worldSetting = s("worldSetting"),
    orientation = PlotOrientation.of(s("orientation")),
    plotDirection = s("plotDirection"),
    tropes = strList("tropes"),
    coverSeed = i("coverSeed"),
    coverImage = s("coverImage"),
    narratorModel = so("narratorModel")?.let { obj("narratorModel").toModelRef() },
    defaultCharacterModel = so("defaultCharacterModel")?.let { obj("defaultCharacterModel").toModelRef() },
    imageModel = so("imageModel")?.let { obj("imageModel").toModelRef() },
    createdAt = l("createdAt"),
    updatedAt = l("updatedAt"),
    archived = b("archived"),
    version = i("version", Society.ARCHIVE_VERSION),
)

// ── 人物 ────────────────────────────────────────────────────────────────────

fun Character.toJson(): JSONObject = jObj(
    "id" to id,
    "name" to name,
    "alias" to alias,
    "gender" to gender.name,
    "age" to age,
    "oneLiner" to oneLiner,
    "personality" to personality,
    "background" to background,
    "appearance" to appearance,
    "speechStyle" to speechStyle,
    "openingLine" to openingLine,
    "avatarUrl" to avatarUrl,
    "importance" to importance.name,
    "model" to model?.toJson(),
    "tags" to tags.map { it.toJson() },
    "isUser" to isUser,
    "isGenerated" to isGenerated,
    "createdAt" to createdAt,
    "updatedAt" to updatedAt,
)

fun JSONObject.toCharacter(): Character = Character(
    id = s("id"),
    name = s("name", "无名"),
    alias = s("alias"),
    gender = Gender.of(s("gender")),
    age = s("age"),
    oneLiner = s("oneLiner"),
    personality = s("personality"),
    background = s("background"),
    appearance = s("appearance"),
    speechStyle = s("speechStyle"),
    openingLine = s("openingLine"),
    avatarUrl = s("avatarUrl"),
    importance = Importance.of(s("importance")),
    model = so("model")?.let { obj("model").toModelRef() },
    tags = arr("tags").let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.toCharacterTag() }
    },
    isUser = b("isUser"),
    isGenerated = b("isGenerated"),
    createdAt = l("createdAt"),
    updatedAt = l("updatedAt"),
)

// ── 关系 ────────────────────────────────────────────────────────────────────

fun Relation.toJson(): JSONObject = jObj(
    "id" to id,
    "fromId" to fromId,
    "toId" to toId,
    "kind" to kind.name,
    "customLabel" to customLabel,
    "intensity" to intensity,
    "note" to note,
    "createdAt" to createdAt,
)

fun JSONObject.toRelation(): Relation = Relation(
    id = s("id"),
    fromId = s("fromId"),
    toId = s("toId"),
    kind = RelationKind.of(s("kind")),
    customLabel = s("customLabel"),
    intensity = i("intensity", 50).coerceIn(0, 100),
    note = s("note"),
    createdAt = l("createdAt"),
)

// ── 消息 ────────────────────────────────────────────────────────────────────

fun Segment.toJson(): JSONObject = when (this) {
    is Segment.Text -> jObj("type" to "text", "content" to content)
    is Segment.Sticker -> jObj("type" to "sticker", "emotion" to emotion, "stickerId" to stickerId)
    is Segment.Picture -> jObj(
        "type" to "picture",
        "url" to url,
        "prompt" to prompt,
        "searchQuery" to searchQuery,
        "style" to style,
        "ratio" to ratio,
        "caption" to caption,
        "state" to state.name,
    )
    is Segment.Transfer -> jObj(
        "type" to "transfer",
        "amount" to amount,
        "to" to to,
        "note" to note,
        "confirmed" to confirmed,
    )
}

fun JSONObject.toSegment(): Segment? = when (s("type")) {
    "text" -> Segment.Text(s("content"))
    "sticker" -> Segment.Sticker(s("emotion"), s("stickerId"))
    "picture" -> Segment.Picture(
        url = s("url"),
        prompt = s("prompt"),
        searchQuery = s("searchQuery"),
        style = s("style"),
        ratio = s("ratio"),
        caption = s("caption"),
        state = runCatching { PictureState.valueOf(s("state", PictureState.READY.name)) }
            .getOrDefault(PictureState.READY),
    )
    "transfer" -> Segment.Transfer(
        amount = d("amount"),
        to = s("to"),
        note = s("note"),
        confirmed = b("confirmed"),
    )
    else -> null
}

fun ChatMessage.toJson(): JSONObject = jObj(
    "id" to id,
    "role" to role.name,
    "charId" to charId,
    "segments" to segments.map { it.toJson() },
    "raw" to raw,
    "ts" to ts,
    "status" to status.name,
    "modelLabel" to modelLabel,
    "tokensIn" to tokensIn,
    "tokensOut" to tokensOut,
    "costUsd" to costUsd,
    "error" to error,
    "attachment" to attachment,
)

fun JSONObject.toChatMessage(): ChatMessage = ChatMessage(
    id = s("id"),
    role = MsgRole.of(s("role")),
    charId = so("charId"),
    segments = arr("segments").let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.toSegment() }
    },
    raw = s("raw"),
    ts = l("ts"),
    status = runCatching { MsgStatus.valueOf(s("status", MsgStatus.DONE.name)) }
        .getOrDefault(MsgStatus.DONE),
    modelLabel = s("modelLabel"),
    tokensIn = i("tokensIn"),
    tokensOut = i("tokensOut"),
    costUsd = d("costUsd"),
    error = s("error"),
    attachment = s("attachment"),
)

// ── 剧情 ────────────────────────────────────────────────────────────────────

fun PlotBeat.toJson(): JSONObject = jObj(
    "ts" to ts,
    "title" to title,
    "detail" to detail,
    "actorId" to actorId,
    "automatic" to automatic,
)

fun JSONObject.toPlotBeat(): PlotBeat = PlotBeat(
    ts = l("ts"),
    title = s("title"),
    detail = s("detail"),
    actorId = so("actorId"),
    automatic = b("automatic"),
)

fun PlotState.toJson(): JSONObject = jObj(
    "act" to act,
    "title" to title,
    "summary" to summary,
    "mood" to mood,
    "hooks" to hooks,
    "beats" to beats.map { it.toJson() },
    "updatedAt" to updatedAt,
)

fun JSONObject.toPlotState(): PlotState = PlotState(
    act = i("act", 1),
    title = s("title", "序章"),
    summary = s("summary"),
    mood = s("mood"),
    hooks = strList("hooks"),
    beats = arr("beats").let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.toPlotBeat() }
    },
    updatedAt = l("updatedAt"),
)

// ── 日志 ────────────────────────────────────────────────────────────────────

fun LogEntry.toJson(): JSONObject = jObj(
    "id" to id,
    "ts" to ts,
    "kind" to kind.name,
    "actor" to actor,
    "title" to title,
    "detail" to detail,
    "modelLabel" to modelLabel,
    "tokensIn" to tokensIn,
    "tokensOut" to tokensOut,
    "costUsd" to costUsd,
    "refMsgId" to refMsgId,
)

fun JSONObject.toLogEntry(): LogEntry = LogEntry(
    id = s("id"),
    ts = l("ts"),
    kind = LogKind.of(s("kind")),
    actor = s("actor"),
    title = s("title"),
    detail = s("detail"),
    modelLabel = s("modelLabel"),
    tokensIn = i("tokensIn"),
    tokensOut = i("tokensOut"),
    costUsd = d("costUsd"),
    refMsgId = so("refMsgId"),
)

// ── 表情包 ──────────────────────────────────────────────────────────────────

fun Sticker.toJson(): JSONObject = jObj(
    "id" to id,
    "name" to name,
    "imageUrl" to imageUrl,
    "emotions" to emotions,
    "source" to source,
    "usageCount" to usageCount,
    "createdAt" to createdAt,
)

fun JSONObject.toSticker(): Sticker = Sticker(
    id = s("id"),
    name = s("name"),
    imageUrl = s("imageUrl"),
    emotions = strList("emotions"),
    source = s("source", Sticker.SOURCE_SYSTEM),
    usageCount = i("usageCount"),
    createdAt = l("createdAt"),
)

// ── 数组工具 ────────────────────────────────────────────────────────────────
//
// 注意：不能写成 `fun List<Society>.toJsonArray()` 这样按元素类型重载。
// 泛型在 JVM 上会被擦除，所有 `List<X>.toJsonArray()` 签名完全相同，编译期即报
// "Platform declaration clash"。因此统一收敛成一个带编码器的泛型函数。

fun <T> List<T>.toJsonArray(encode: (T) -> JSONObject): JSONArray =
    JSONArray().also { a -> forEach { item -> a.put(encode(item)) } }

fun <T> JSONArray.decodeList(decode: (JSONObject) -> T): List<T> =
    (0 until length()).mapNotNull { idx -> optJSONObject(idx)?.let(decode) }

fun JSONArray.toSocietyList(): List<Society> = decodeList { it.toSociety() }

fun JSONArray.toCharacterList(): List<Character> = decodeList { it.toCharacter() }

fun JSONArray.toRelationList(): List<Relation> = decodeList { it.toRelation() }

fun JSONArray.toMessageList(): List<ChatMessage> = decodeList { it.toChatMessage() }

fun JSONArray.toStickerList(): List<Sticker> = decodeList { it.toSticker() }

fun JSONArray.toPlotBeatList(): List<PlotBeat> = decodeList { it.toPlotBeat() }

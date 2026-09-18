package com.kith.app.ai.engine

import com.kith.app.ai.ChatTurn
import com.kith.app.ai.LlmClient
import com.kith.app.ai.prompt.Prompts
import com.kith.app.core.Ids
import com.kith.app.core.arr
import com.kith.app.core.extractJsonBlock
import com.kith.app.core.i
import com.kith.app.core.obj
import com.kith.app.core.s
import com.kith.app.core.so
import com.kith.app.core.strList
import com.kith.app.data.catalog.ModelCatalog
import com.kith.app.data.settings.SettingsSnapshot
import com.kith.app.data.store.SocietyBundle
import com.kith.app.domain.Character
import com.kith.app.domain.CharacterTag
import com.kith.app.domain.Gender
import com.kith.app.domain.Importance
import com.kith.app.domain.LogEntry
import com.kith.app.domain.LogKind
import com.kith.app.domain.ModelRef
import com.kith.app.domain.ModelTier
import com.kith.app.domain.PlotBeat
import com.kith.app.domain.PlotState
import com.kith.app.domain.Relation
import com.kith.app.domain.RelationKind
import org.json.JSONObject

/**
 * 旁白一次推进的产出。
 */
data class NarratorOutcome(
    val narrative: String = "",
    val plot: PlotState = PlotState(),
    /** 本次新生成、并已接入关系网的 NPC */
    val newCharacters: List<Character> = emptyList(),
    /** 新 NPC 带来的关系连线 */
    val newRelations: List<Relation> = emptyList(),
    val logs: List<LogEntry> = emptyList(),
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val costUsd: Double = 0.0,
    val error: String = "",
) {
    val ok: Boolean get() = error.isEmpty()
    val changed: Boolean get() = newCharacters.isNotEmpty() || plot != PlotState()
}

/**
 * 模型解析器 —— 把「档位」翻译成「用户真正能调用的某个模型」。
 *
 * 这里有个容易被忽略的坑：目录里的模型（如 `openai/gpt-4o`）只有在用户
 * **配置了对应厂商的接入点**时才可用。所以自动分配不能只按价格挑，还必须
 * 限定在用户已经配好 Key 的厂商范围内，否则挑出来也调不通。
 */
object ModelResolver {

    /**
     * 按档位挑一个用户可用的模型。
     *
     * @param preferredVendor 优先厂商（通常是旁白自己用的那家，或用户给人物设的默认厂商）
     */
    suspend fun pick(
        catalog: ModelCatalog,
        settings: SettingsSnapshot,
        tier: ModelTier,
        requireVision: Boolean = false,
        preferredVendor: String? = null,
    ): ModelRef? {
        val configured = settings.endpoints
            .filter { it.kind == com.kith.app.domain.EndpointKind.LLM }
            .associateBy { it.vendor }

        if (configured.isEmpty()) return null

        // 优先顺序：指定厂商 → 用户默认人物模型的厂商 → 其余已配置厂商
        val order = buildList {
            preferredVendor?.takeIf { it in configured }?.let { add(it) }
            settings.savedModels.firstOrNull()
                ?.vendor?.takeIf { it in configured && it !in this }?.let { add(it) }
            settings.endpoints.forEach { ep ->
                if (ep.kind == com.kith.app.domain.EndpointKind.LLM && ep.vendor !in this) {
                    add(ep.vendor)
                }
            }
        }

        for (vendor in order) {
            val endpoint = configured[vendor] ?: continue
            val picked = catalog.pickForTier(tier, requireVision, vendorKey = vendor)
            if (picked != null) {
                return ModelRef(
                    endpointId = endpoint.id,
                    modelId = picked.id,
                    label = picked.name,
                    vendor = picked.vendorKey,
                )
            }
        }

        // 目录里没有该厂商的记录（比如用户用的是自建中转），退回到已保存的配置
        return settings.savedModels.firstOrNull { it.endpointId in settings.endpoints.map { e -> e.id } }
    }
}

/**
 * 旁白引擎。
 *
 * 职责有三，对应产品需求里的第三条：
 *  1. **推进剧情** —— 根据世界观、走向、已有事件，决定接下来发生什么；
 *  2. **生成新 NPC** —— 剧情需要新角色时，创造一个**有背景、有关系、能和前面
 *     剧情无缝衔接**的人，而不是凭空冒出来的路人；
 *  3. **自动分配模型** —— 判断这个 NPC 有多重要，据此给它配一个档位合适的模型，
 *     既不会给路人用旗舰模型烧钱，也不会让关键角色用最便宜的模型出戏。
 */
class NarratorEngine(
    private val llm: LlmClient,
    private val catalog: ModelCatalog,
) {

    suspend fun advance(
        bundle: SocietyBundle,
        settings: SettingsSnapshot,
        userHint: String = "",
        needNewNpc: Boolean = false,
        maxNewNpcs: Int = 2,
    ): NarratorOutcome {
        val society = bundle.society

        // 1. 解析旁白模型
        val resolved = settings.resolve(society.narratorModel)
            ?: return NarratorOutcome(
                error = "还没有给旁白配置模型。请到「设置 → 模型配置」里为这个社会指定旁白使用的模型。",
            )
        val (endpoint, ref) = resolved

        // 2. 构造 prompt
        val system = Prompts.narratorSystem(society, bundle.characters, bundle.plot)
        val task = Prompts.narratorTask(userHint, needNewNpc, maxNewNpcs)
        val turns = listOf(ChatTurn.system(system), ChatTurn.user(task))

        // 3. 调用
        val result = llm.complete(
            endpoint = endpoint,
            modelId = ref.modelId,
            turns = turns,
            temperature = 0.9,
            maxTokens = 3000,
            jsonMode = true,
        )
        if (!result.ok) {
            return NarratorOutcome(
                error = result.error,
                inputTokens = result.inputTokens,
                outputTokens = result.outputTokens,
                logs = listOf(
                    log(
                        LogKind.NARRATOR, "旁白", "推进剧情失败",
                        detail = result.error, modelLabel = ref.display(),
                        tokensIn = result.inputTokens, tokensOut = result.outputTokens,
                    ),
                ),
            )
        }

        val json = extractJsonBlock(result.text)
            ?: return NarratorOutcome(
                error = "旁白返回的内容不是合法 JSON，无法解析剧情推进结果。",
                inputTokens = result.inputTokens,
                outputTokens = result.outputTokens,
            )

        val root = runCatching { JSONObject(json) }.getOrElse { e ->
            return NarratorOutcome(
                error = "旁白返回的 JSON 解析失败：${e.message}",
                inputTokens = result.inputTokens,
                outputTokens = result.outputTokens,
            )
        }

        val cost = catalog.estimateCostUsd(ref.modelId, result.inputTokens, result.outputTokens)
        val narrative = root.s("narrative")
        val logs = ArrayList<LogEntry>()

        logs += log(
            LogKind.NARRATOR, "旁白", "推进了剧情",
            detail = narrative,
            modelLabel = ref.display(),
            tokensIn = result.inputTokens, tokensOut = result.outputTokens, costUsd = cost,
        )

        // 4. 生成新人物
        val (newChars, charLogs) = buildNewCharacters(root, bundle, settings, ref)
        logs += charLogs

        // 5. 建立新关系（含指向已有角色与新角色的两种）
        val relations = buildRelations(root, bundle, newChars, settings, logs)

        // 6. 回填新人物在关系网中的分数（有了关系数才能算结构分）
        val scored = newChars.map { c ->
            val rels = relations.filter { it.fromId == c.character.id || it.toId == c.character.id }
            val score = ImportanceScorer.finalScore(
                proposedByModel = c.importanceScoreHint,
                relations = rels,
                taggedByUser = c.character.tags.isNotEmpty(),
            )
            c.character.copy(importance = Importance.ofScore(score))
        }

        // 7. 更新剧情状态
        val plot = mergePlot(root, bundle.plot, scored)

        return NarratorOutcome(
            narrative = narrative,
            plot = plot,
            newCharacters = scored,
            newRelations = relations,
            logs = logs,
            inputTokens = result.inputTokens,
            outputTokens = result.outputTokens,
            costUsd = cost,
        )
    }

    // ── 新人物 ──────────────────────────────────────────────────────────────

    /** 带评分提示的中间态人物。 */
    private data class PendingCharacter(
        val character: Character,
        val importanceScoreHint: Int,
        val relationSpecs: List<RelationSpec>,
    )

    private data class RelationSpec(
        val targetKey: String,
        val kind: RelationKind,
        val customLabel: String,
        val intensity: Int,
        val note: String,
    )

    private suspend fun buildNewCharacters(
        root: JSONObject,
        bundle: SocietyBundle,
        settings: SettingsSnapshot,
        narratorRef: ModelRef,
    ): Pair<List<PendingCharacter>, List<LogEntry>> {
        val arr = root.arr("newCharacters")
        if (arr.length() == 0) return emptyList<PendingCharacter>() to emptyList()

        val out = ArrayList<PendingCharacter>(arr.length())
        val logs = ArrayList<LogEntry>()

        for (idx in 0 until arr.length()) {
            val o = arr.optJSONObject(idx) ?: continue
            val name = o.s("name").trim()
            if (name.isEmpty()) continue

            val proposed = o.i("importanceScore", ImportanceScorer.DEFAULT_PROPOSED)
            val reason = o.s("importanceReason")

            val relationSpecs = o.arr("relations").let { ra ->
                (0 until ra.length()).mapNotNull { ri ->
                    val r = ra.optJSONObject(ri) ?: return@mapNotNull null
                    val target = r.s("toCharacterId").trim()
                    if (target.isEmpty()) return@mapNotNull null
                    RelationSpec(
                        targetKey = target,
                        kind = RelationKind.of(r.s("kind")),
                        customLabel = r.s("customLabel"),
                        intensity = r.i("intensity", 50).coerceIn(0, 100),
                        note = r.s("note"),
                    )
                }
            }

            // 先按模型给的分推出档位，据此分配模型
            val tier = ImportanceScorer.tierOf(Importance.ofScore(proposed))
            val model = if (settings.narratorAutoAssignModel) {
                ModelResolver.pick(
                    catalog = catalog,
                    settings = settings,
                    tier = tier,
                    requireVision = false,
                    preferredVendor = narratorRef.vendor,
                )
            } else {
                bundle.society.defaultCharacterModel
            }

            val now = System.currentTimeMillis()
            val char = Character(
                id = Ids.new("chr"),
                name = name,
                alias = o.s("alias"),
                gender = Gender.of(o.s("gender")),
                age = o.s("age"),
                oneLiner = o.s("oneLiner"),
                personality = o.s("personality"),
                background = o.s("background"),
                appearance = o.s("appearance"),
                speechStyle = o.s("speechStyle"),
                importance = Importance.ofScore(proposed),
                model = model,
                tags = emptyList(),
                isUser = false,
                isGenerated = true,
                createdAt = now,
                updatedAt = now,
            )

            out += PendingCharacter(char, proposed, relationSpecs)

            logs += log(
                LogKind.NARRATOR, "旁白", "引入了新人物「$name」",
                detail = buildString {
                    appendLine(o.s("oneLiner"))
                    if (reason.isNotEmpty()) appendLine("重要性判断依据：$reason")
                    appendLine("拟定档位：${tier.label}")
                    append("分配模型：${model?.display() ?: "未配置（可在人物详情里手动指定）"}")
                },
                modelLabel = narratorRef.display(),
            )
        }

        return out to logs
    }

    // ── 关系 ────────────────────────────────────────────────────────────────

    /**
     * 把旁白给的关系规格落实成真实的 Relation。
     *
     * 目标解析顺序：**已有角色的 id** → **本批次新人物的姓名** → **已有角色的姓名**。
     * 模型经常会把 id 写成名字，硬要求它只输出 id 不现实；这里做一次宽进严出的对齐，
     * 解析不出来的关系会被丢弃并记日志，而不是产生一条指向空气的连线。
     */
    private fun buildRelations(
        root: JSONObject,
        bundle: SocietyBundle,
        newChars: List<PendingCharacter>,
        settings: SettingsSnapshot,
        logs: MutableList<LogEntry>,
    ): List<Relation> {
        val out = ArrayList<Relation>()

        val byId = bundle.characters.associateBy { it.id }
        val byName = bundle.characters.associateBy { it.name }
        val newByName = newChars.associateBy { it.character.name }

        newChars.forEach { pending ->
            val self = pending.character
            pending.relationSpecs.forEach { spec ->
                val target = byId[spec.targetKey]
                    ?: newByName[spec.targetKey]?.character
                    ?: byName[spec.targetKey]

                if (target == null) {
                    logs += log(
                        LogKind.NARRATOR, "旁白", "丢弃了一条无法解析的关系",
                        detail = "「${self.name}」→「${spec.targetKey}」：找不到对应的人物，" +
                            "可能是旁白编造了一个不存在的人。这条连线已跳过。",
                    )
                    return@forEach
                }
                if (target.id == self.id) return@forEach

                // 去重：同一对人物+同一类型不重复建线
                val exists = out.any {
                    (it.fromId == self.id && it.toId == target.id) ||
                        (it.toId == self.id && it.fromId == target.id)
                } || bundle.relations.any {
                    (it.fromId == self.id && it.toId == target.id) ||
                        (it.toId == self.id && it.fromId == target.id)
                }
                if (exists) return@forEach

                out += Relation(
                    id = Ids.new("rel"),
                    fromId = self.id,
                    toId = target.id,
                    kind = spec.kind,
                    customLabel = spec.customLabel,
                    intensity = spec.intensity,
                    note = spec.note,
                    createdAt = System.currentTimeMillis(),
                )
            }

            if (settings.narratorAutoLinkRelations && out.none {
                    it.fromId == self.id || it.toId == self.id
                }
            ) {
                // 新人物一条关系都没接上 —— 这是「无缝衔接」要求的失败情形，
                // 明确记日志让用户看得见，而不是悄悄放一个孤岛进关系图。
                logs += log(
                    LogKind.NARRATOR, "旁白", "新人物「${self.name}」没有接上任何关系",
                    detail = "旁白没有为 TA 指定有效的关系，这个人现在在关系图上是孤立的。" +
                        "可以在人物详情里手动补一条连线，或让旁白重新生成。",
                )
            }
        }

        return out
    }

    // ── 剧情状态 ────────────────────────────────────────────────────────────

    private fun mergePlot(
        root: JSONObject,
        old: PlotState,
        newChars: List<Character>,
    ): PlotState {
        val upd = root.optJSONObject("plotUpdate")
        val beats = root.arr("beats").let { ba ->
            (0 until ba.length()).mapNotNull { bi ->
                val b = ba.optJSONObject(bi) ?: return@mapNotNull null
                val title = b.s("title").trim()
                if (title.isEmpty()) return@mapNotNull null
                val actorRaw = b.so("actorId")
                // 旁白可能用名字引用人物，这里做一次对齐
                val actorId = newChars.firstOrNull { it.name == actorRaw }?.id ?: actorRaw
                PlotBeat(
                    ts = System.currentTimeMillis(),
                    title = title,
                    detail = b.s("detail"),
                    actorId = actorId,
                    automatic = true,
                )
            }
        }

        val hooks = root.strList("hooks").ifEmpty { old.hooks }

        return PlotState(
            act = upd?.i("act", root.i("act", old.act)) ?: old.act,
            title = upd?.s("title")?.takeIf { it.isNotEmpty() } ?: old.title,
            summary = upd?.s("summary")?.takeIf { it.isNotEmpty() } ?: old.summary,
            mood = upd?.s("mood")?.takeIf { it.isNotEmpty() } ?: old.mood,
            hooks = hooks,
            // 只保留最近 60 条，避免长期游玩后剧情文件无限膨胀
            beats = (old.beats + beats).takeLast(60),
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun log(
        kind: LogKind,
        actor: String,
        title: String,
        detail: String = "",
        modelLabel: String = "",
        tokensIn: Int = 0,
        tokensOut: Int = 0,
        costUsd: Double = 0.0,
    ) = LogEntry(
        id = Ids.new("log"),
        ts = System.currentTimeMillis(),
        kind = kind,
        actor = actor,
        title = title,
        detail = detail,
        modelLabel = modelLabel,
        tokensIn = tokensIn,
        tokensOut = tokensOut,
        costUsd = costUsd,
    )
}

package com.kith.app.ui.society

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kith.app.ai.engine.Money
import com.kith.app.ai.engine.NarratorOutcome
import com.kith.app.core.Ids
import com.kith.app.data.settings.GraphLayout
import com.kith.app.data.store.SocietyBundle
import com.kith.app.domain.Character
import com.kith.app.domain.ChatMessage
import com.kith.app.domain.EndpointKind
import com.kith.app.domain.LogEntry
import com.kith.app.domain.ModelRef
import com.kith.app.domain.MsgRole
import com.kith.app.domain.Relation
import com.kith.app.domain.Segment
import com.kith.app.data.store.CharacterImporter
import com.kith.app.kithGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 社会内这次「选模型」要填到哪个槽位。 */
enum class SocietyPickTarget(
    val title: String,
    val kind: EndpointKind,
    val subtitle: String = "",
) {
    NARRATOR(
        "旁白使用的模型",
        EndpointKind.LLM,
        "旁白负责推进剧情、生成 NPC、判断重要性。",
    ),
    DEFAULT_CHARACTER(
        "新人物默认模型",
        EndpointKind.LLM,
        "旁白生成 NPC 时的兜底；单个角色可再单独覆盖。",
    ),
    IMAGE(
        "文生图模型",
        EndpointKind.IMAGE,
        "用于生成人物头像与聊天里的配图。",
    ),
    CHARACTER(
        "这个人物使用的模型",
        EndpointKind.LLM,
        "留空则跟随社会的默认人物模型。",
    ),
}

/** 社会主界面的状态。 */
data class SocietyUiState(
    val loading: Boolean = true,
    val bundle: SocietyBundle? = null,
    val layout: GraphLayout = GraphLayout.TREE,
    val logs: List<LogEntry> = emptyList(),
    val narratorRunning: Boolean = false,
    val narratorText: String = "",
    val notice: String = "",
    val toast: String = "",
    /** 正在编辑的人物草稿；非空即表示人物编辑界面处于打开状态 */
    val characterDraft: Character? = null,
    /** 正在解析导入的人物文件 */
    val importingCharacters: Boolean = false,
    /** 用户长按拖拽出来的节点位移（dp，id → [x, y]），叠加在自动布局之上 */
    val nodePositions: Map<String, List<Float>> = emptyMap(),
) {
    val totalCostUsd: Double get() = logs.sumOf { it.costUsd }
    val tokensIn: Int get() = logs.sumOf { it.tokensIn }
    val tokensOut: Int get() = logs.sumOf { it.tokensOut }
}

class SocietyViewModel(private val societyId: String) : ViewModel() {

    private val graph = kithGraph

    private val _state = MutableStateFlow(SocietyUiState())
    val state: StateFlow<SocietyUiState> = _state.asStateFlow()

    /** 这次选模型是为哪个槽位、为哪个人物发起的。 */
    private var pendingTarget: SocietyPickTarget? = null
    private var pendingCharacterId: String? = null

    init {
        reload()
        // 选模型页面的结果回传。ViewModel 跨导航存活，所以跳走再回来也能收到。
        viewModelScope.launch {
            graph.pickBus.result.collect { ref ->
                if (ref == null) return@collect
                applyPick(ref)
                graph.pickBus.consume()
            }
        }
    }

    fun reload() {
        viewModelScope.launch {
            val bundle = graph.store.load(societyId)
            val logs = graph.store.readLogs(societyId, 400)
            val positions = graph.store.loadNodePositions(societyId)
            // 模型引用清洗：目录按实测裁剪后，存档里保存的旧 id（老目录的
            // 日期后缀名）会悬空 —— 面板显示旧名、调用直接 400。
            val sanitized = bundle?.let { sanitizeModelRefs(it) }
            if (sanitized != null) {
                graph.store.saveSociety(sanitized.society)
                graph.store.saveCharacters(societyId, sanitized.characters)
            }
            _state.value = _state.value.copy(
                loading = false,
                bundle = sanitized ?: bundle,
                logs = logs,
                layout = graph.settings.current.graphLayout,
                nodePositions = positions,
            )
        }
    }

    /**
     * 把存档里的模型引用对齐到现存目录条目。
     *
     * 匹配顺序：id 原样在目录 → 剥掉日期后缀（-0423/-0731/-0813/20260420…）
     * 后的裸名（含与不含 vendor 前缀）。能映射的更新 id 并把显示名刷成目录
     * 最新名称；映射不到的保持原样（调用时由服务端给出可理解的报错）。
     * 无任何变化时返回 null，避免无谓的落盘。
     */
    private suspend fun sanitizeModelRefs(bundle: SocietyBundle): SocietyBundle? {
        val ids = graph.catalog.current().models.map { it.id }.toSet()
        if (ids.isEmpty()) return null
        val nameOf: (String) -> String? = { id -> graph.catalog.find(id)?.name }

        fun fix(ref: ModelRef?): ModelRef? {
            if (ref == null) return ref
            if (ref.modelId in ids) {
                // id 有效，只把显示名刷新成目录最新名称
                val fresh = nameOf(ref.modelId) ?: return ref
                return if (fresh != ref.label) ref.copy(label = fresh) else ref
            }
            val bare = ref.modelId.substringAfter('/', "")
            val stripped = bare.replace(Regex("-(?:\\d{8}|\\d{2}-\\d{4}|\\d{4})$"), "")
            val target = listOf("deepseek/$stripped", stripped, ref.modelId)
                .firstOrNull { it in ids } ?: return ref
            return ref.copy(modelId = target, label = nameOf(target) ?: ref.label)
        }

        var society = bundle.society
        var characters = bundle.characters
        var changed = false

        val n = fix(society.narratorModel)
        if (n != society.narratorModel) { society = society.copy(narratorModel = n); changed = true }
        val d = fix(society.defaultCharacterModel)
        if (d != society.defaultCharacterModel) { society = society.copy(defaultCharacterModel = d); changed = true }
        val img = fix(society.imageModel)
        if (img != society.imageModel) { society = society.copy(imageModel = img); changed = true }
        val fixedChars = characters.map { c ->
            val m = fix(c.model)
            if (m != c.model) { changed = true; c.copy(model = m) } else c
        }
        characters = fixedChars

        return if (changed) bundle.copy(society = society, characters = characters) else null
    }

    // ── 节点拖拽 ────────────────────────────────────────────────────────────

    /** 拖拽中的增量。只改内存，跟手；松手时由 [persistNodePositions] 落盘。 */
    fun dragNode(id: String, dx: Float, dy: Float) {
        val cur = _state.value.nodePositions[id] ?: listOf(0f, 0f)
        _state.value = _state.value.copy(
            nodePositions = _state.value.nodePositions +
                (id to listOf(cur[0] + dx, cur[1] + dy)),
        )
    }

    /** 把当前位置写进 layout.json。清掉已删除人物的残留位移。 */
    fun persistNodePositions() {
        val ids = _state.value.bundle?.characters?.map { it.id }?.toSet() ?: return
        val positions = _state.value.nodePositions.filterKeys { it in ids }
        viewModelScope.launch { graph.store.saveNodePositions(societyId, positions) }
    }

    /**
     * 把一个「未连线人物」从下方网格钉进画布（绝对坐标，节点中心，dp）。
     * 有了这条位置记录后，图组件会把他渲染为画布上的自由节点。
     */
    fun placeNode(id: String, x: Float, y: Float) {
        _state.value = _state.value.copy(
            nodePositions = _state.value.nodePositions + (id to listOf(x, y)),
        )
        persistNodePositions()
    }

    /** 把已放进画布的「未连线人物」放回下方网格（删掉位置记录）。 */
    fun unplaceNode(id: String) {
        _state.value = _state.value.copy(nodePositions = _state.value.nodePositions - id)
        persistNodePositions()
    }

    fun setLayout(layout: GraphLayout) {
        graph.settings.setGraphLayout(layout)
        _state.value = _state.value.copy(layout = layout)
    }

    fun consumeToast() {
        _state.value = _state.value.copy(toast = "")
    }

    // ── 人物增删改 ──────────────────────────────────────────────────────────

    fun saveCharacter(c: Character) {
        viewModelScope.launch {
            val b = _state.value.bundle ?: return@launch
            val list = if (b.characters.any { it.id == c.id }) {
                b.characters.map { if (it.id == c.id) c else it }
            } else {
                b.characters + c
            }
            graph.store.saveCharacters(societyId, list)
            reload()
        }
    }

    fun deleteCharacter(c: Character) {
        viewModelScope.launch {
            val b = _state.value.bundle ?: return@launch
            graph.store.saveCharacters(societyId, b.characters.filterNot { it.id == c.id })
            // 同时清掉挂在 TA 身上的关系，避免留下指向空气的连线
            graph.store.saveRelations(
                societyId,
                b.relations.filterNot { it.fromId == c.id || it.toId == c.id },
            )
            reload()
        }
    }

    // ── 人物草稿 ────────────────────────────────────────────────────────────

    fun beginNewCharacter() {
        val now = System.currentTimeMillis()
        val b = _state.value.bundle
        _state.value = _state.value.copy(
            characterDraft = Character(
                id = Ids.new("chr"),
                name = "",
                model = b?.society?.defaultCharacterModel,
                isUser = b?.characters?.none { it.isUser } ?: true,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    fun beginEditCharacter(c: Character) {
        _state.value = _state.value.copy(characterDraft = c)
    }

    fun updateDraft(transform: (Character) -> Character) {
        val d = _state.value.characterDraft ?: return
        _state.value = _state.value.copy(characterDraft = transform(d))
    }

    fun cancelDraft() {
        _state.value = _state.value.copy(characterDraft = null)
    }

    fun isEditingExistingDraft(): Boolean {
        val d = _state.value.characterDraft ?: return false
        return _state.value.bundle?.characters?.any { it.id == d.id } == true
    }

    fun commitDraft() {
        val draft = _state.value.characterDraft ?: return
        _state.value = _state.value.copy(characterDraft = null)
        if (draft.name.isBlank()) return
        saveCharacter(draft.copy(updatedAt = System.currentTimeMillis()))
    }

    // ── 关系 ────────────────────────────────────────────────────────────────

    fun addRelation(relation: Relation) {
        viewModelScope.launch {
            val b = _state.value.bundle ?: return@launch
            graph.store.saveRelations(societyId, b.relations + relation)
            reload()
        }
    }

    fun removeRelation(relation: Relation) {
        viewModelScope.launch {
            val b = _state.value.bundle ?: return@launch
            graph.store.saveRelations(societyId, b.relations.filterNot { it.id == relation.id })
            reload()
        }
    }

    // ── 人物文件导入 ────────────────────────────────────────────────────────

    /**
     * 从一段 JSON 导入人物。
     *
     * 支持 Kith 自家格式与喵咚角色卡格式，解析在 [CharacterImporter] 里。
     * 按名字去重：社会里已有同名人物的不重复添加。
     */
    fun importCharacters(raw: String) {
        if (_state.value.importingCharacters) return
        viewModelScope.launch {
            _state.value = _state.value.copy(importingCharacters = true)
            runCatching { CharacterImporter.import(raw) }
                .onSuccess { (chars, skipped) ->
                    val existing = _state.value.bundle?.characters.orEmpty()
                    val fresh = chars.filter { c -> existing.none { it.name == c.name } }
                    if (fresh.isNotEmpty()) {
                        graph.store.saveCharacters(societyId, existing + fresh)
                    }
                    _state.value = _state.value.copy(
                        importingCharacters = false,
                        toast = when {
                            fresh.isEmpty() -> "没有新增人物（社会里已有同名人物）"
                            else -> "已导入 ${fresh.size} 位人物" +
                                if (skipped > 0) "，跳过 $skipped 个无效项" else ""
                        },
                    )
                    reload()
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        importingCharacters = false,
                        toast = "导入失败：${e.message ?: "未知错误"}",
                    )
                }
        }
    }

    // ── 模型 ────────────────────────────────────────────────────────────────

    /** 由界面调用：登记意图并跳转到配置页。 */
    fun requestPick(target: SocietyPickTarget, characterId: String? = null) {
        pendingTarget = target
        pendingCharacterId = characterId
        graph.pickBus.start(target.kind, target.title, target.subtitle)
    }

    private fun applyPick(ref: ModelRef) {
        val b = _state.value.bundle
        when (pendingTarget) {
            SocietyPickTarget.NARRATOR -> setNarratorModel(ref)
            SocietyPickTarget.DEFAULT_CHARACTER -> setDefaultCharacterModel(ref)
            SocietyPickTarget.IMAGE -> setImageModel(ref)
            SocietyPickTarget.CHARACTER -> {
                val d = _state.value.characterDraft
                when {
                    // 正在编辑人物：写进草稿，不直接落盘
                    d != null && (pendingCharacterId == null || d.id == pendingCharacterId) ->
                        _state.value = _state.value.copy(characterDraft = d.copy(model = ref))

                    else -> b?.character(pendingCharacterId)?.let { assignModel(it, ref) }
                }
            }
            null -> Unit
        }
        pendingTarget = null
        pendingCharacterId = null
    }

    /** 手动给某个人物换模型。 */
    fun assignModel(c: Character, ref: ModelRef?) {
        saveCharacter(c.copy(model = ref, updatedAt = System.currentTimeMillis()))
    }

    /** 改这个社会的旁白模型。旁白没模型就没法推进剧情，所以必须能在应用内改。 */
    fun setNarratorModel(ref: ModelRef?) {
        viewModelScope.launch {
            val b = _state.value.bundle ?: return@launch
            graph.store.saveSociety(
                b.society.copy(narratorModel = ref, updatedAt = System.currentTimeMillis()),
            )
            reload()
        }
    }

    /** 改社会默认的人物模型（新建角色时用它）。 */
    fun setDefaultCharacterModel(ref: ModelRef?) {
        viewModelScope.launch {
            val b = _state.value.bundle ?: return@launch
            graph.store.saveSociety(
                b.society.copy(defaultCharacterModel = ref, updatedAt = System.currentTimeMillis()),
            )
            reload()
        }
    }

    /** 改文生图模型（头像与聊天配图）。 */
    fun setImageModel(ref: ModelRef?) {
        viewModelScope.launch {
            val b = _state.value.bundle ?: return@launch
            graph.store.saveSociety(
                b.society.copy(imageModel = ref, updatedAt = System.currentTimeMillis()),
            )
            reload()
        }
    }

    // ── 旁白 ────────────────────────────────────────────────────────────────

    /**
     * 让旁白推进剧情。这是「旁白 AI 用来帮助剧情走向」的主入口。
     */
    fun runNarrator(hint: String, needNewNpc: Boolean) {
        val b = _state.value.bundle ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                narratorRunning = true,
                narratorText = "",
                notice = "",
            )
            val outcome = graph.narrator.advance(
                bundle = b,
                settings = graph.settings.current,
                userHint = hint,
                needNewNpc = needNewNpc,
            )
            applyOutcome(outcome)
        }
    }

    private suspend fun applyOutcome(outcome: NarratorOutcome) {
        val b = _state.value.bundle
        if (b == null) {
            _state.value = _state.value.copy(narratorRunning = false)
            return
        }
        if (!outcome.ok) {
            graph.store.appendLogs(societyId, outcome.logs)
            _state.value = _state.value.copy(
                narratorRunning = false,
                notice = outcome.error,
                logs = graph.store.readLogs(societyId, 400),
            )
            return
        }

        if (outcome.newCharacters.isNotEmpty()) {
            graph.store.saveCharacters(societyId, b.characters + outcome.newCharacters)
        }
        if (outcome.newRelations.isNotEmpty()) {
            graph.store.saveRelations(societyId, b.relations + outcome.newRelations)
        }
        graph.store.savePlot(societyId, outcome.plot)
        graph.store.appendLogs(societyId, outcome.logs)        // 旁白的叙述本身也作为一条消息存进「旁白会话」，保证日志之外还能回看
        graph.store.appendMessage(
            societyId = societyId,
            charId = NARRATOR_THREAD,
            message = ChatMessage(
                id = Ids.new("msg"),
                role = MsgRole.NARRATOR,
                segments = listOf(Segment.Text(outcome.narrative)),
                raw = outcome.narrative,
                ts = System.currentTimeMillis(),
                costUsd = outcome.costUsd,
                tokensIn = outcome.inputTokens,
                tokensOut = outcome.outputTokens,
            ),
        )

        // ── 本地审核（可选，默认关）──
        // 开启时对旁白叙述跑两级检查：最低层次体检 + Qwen3Guard 安全分类。
        // 未通过也保留内容并写日志（绝不静默丢弃），只给用户明确提示。
        val guardNotice = if (graph.settings.current.guardEnabled) {
            when (val v = graph.guard.check(outcome.narrative)) {
                is com.kith.app.ai.guard.GuardVerdict.Unsafe -> {
                    graph.store.appendLogs(
                        societyId,
                        listOf(
                            LogEntry(
                                id = Ids.new("log"),
                                ts = System.currentTimeMillis(),
                                kind = com.kith.app.domain.LogKind.SYSTEM,
                                actor = "审核",
                                title = "旁白内容未通过本地审核",
                                detail = "类别：${v.categories}\n原文已保留在旁白会话中",
                            ),
                        ),
                    )
                    "⚠️ 旁白内容未通过本地审核（${v.categories}）"
                }
                is com.kith.app.ai.guard.GuardVerdict.Error ->
                    "本地审核未完成：${v.message}"
                com.kith.app.ai.guard.GuardVerdict.Ok -> null
            }
        } else null

        val fresh = graph.store.load(societyId)
        _state.value = _state.value.copy(
            bundle = fresh,
            narratorRunning = false,
            narratorText = outcome.narrative,
            logs = graph.store.readLogs(societyId, 400),
            toast = buildString {
                append("剧情已推进")
                if (outcome.newCharacters.isNotEmpty()) {
                    append("，引入 ${outcome.newCharacters.size} 位新人物")
                }
                if (outcome.costUsd > 0) append("，花费 ${Money.cny(outcome.costUsd)}")
                if (guardNotice != null) append("。$guardNotice")
            },
        )
    }

    companion object {
        /** 旁白叙述单独存一条会话，charId 用固定值。 */
        const val NARRATOR_THREAD = "__narrator__"

        fun factory(societyId: String) = viewModelFactory {
            initializer { SocietyViewModel(societyId) }
        }
    }
}

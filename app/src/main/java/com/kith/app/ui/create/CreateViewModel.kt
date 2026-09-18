package com.kith.app.ui.create

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kith.app.core.Ids
import com.kith.app.data.store.SocietyBundle
import com.kith.app.domain.Character
import com.kith.app.domain.EndpointKind
import com.kith.app.domain.ModelRef
import com.kith.app.domain.PlotOrientation
import com.kith.app.domain.PlotState
import com.kith.app.domain.PlotTemplate
import com.kith.app.domain.PlotTemplates
import com.kith.app.domain.Society
import com.kith.app.kithGraph
import kotlinx.coroutines.launch

/** 创建社会时，这次「选模型」是要填到哪个槽位。 */
enum class CreatePickTarget(
    val title: String,
    val kind: EndpointKind,
    val subtitle: String = "",
) {
    NARRATOR(
        "旁白使用的模型",
        EndpointKind.LLM,
        "旁白负责推进剧情、生成 NPC、判断重要性。长文本、需要理解全局，建议别用最小的模型。",
    ),
    DEFAULT_CHARACTER(
        "人物默认模型",
        EndpointKind.LLM,
        "旁白生成新人物时的默认模型，单个角色可以再单独覆盖。",
    ),
    IMAGE(
        "文生图模型",
        EndpointKind.IMAGE,
        "用于生成人物头像与聊天里的配图。",
    ),
    CHARACTER_DRAFT(
        "这个人物使用的模型",
        EndpointKind.LLM,
        "留空则跟随社会的默认人物模型。",
    ),
}

/**
 * 创建社会的草稿状态。
 *
 * **为什么必须放进 ViewModel**：创建过程中的模型配置改成了独立页面，
 * 用户会从创建页跳走、选完再跳回来。Compose 的 `remember` 在导航离开时会随
 * composable 一起被销毁 —— 表单填了一半的内容会全部丢掉。而 ViewModel 绑定在
 * NavBackStackEntry 上，只要创建页还在返回栈里就活着，所以草稿能完整保住。
 */
class CreateViewModel : ViewModel() {

    private val graph = kithGraph

    var name by mutableStateOf("")
        private set
    var orientation by mutableStateOf(PlotOrientation.BG)
        private set
    var worldSetting by mutableStateOf("")
        private set
    var plotDirection by mutableStateOf("")
        private set
    var tropes by mutableStateOf<List<String>>(emptyList())
        private set
    var characters by mutableStateOf<List<Character>>(emptyList())
        private set

    var narratorModel by mutableStateOf<ModelRef?>(null)
        private set
    var defaultCharacterModel by mutableStateOf<ModelRef?>(null)
        private set
    var imageModel by mutableStateOf<ModelRef?>(null)
        private set

    /** 正在编辑的人物草稿。非空即表示人物编辑界面处于打开状态。 */
    var characterDraft by mutableStateOf<Character?>(null)
        private set

    var creating by mutableStateOf(false)
        private set
    var error by mutableStateOf("")
        private set

    /** 当前这次选模型是为哪个槽位发起的。 */
    private var pendingTarget: CreatePickTarget? = null

    /** 已选的模板，用于界面高亮。 */
    var selectedTemplate by mutableStateOf(PlotTemplates.all.first())
        private set

    init {
        // 订阅选模型页面的结果。ViewModel 跨导航存活，所以这条链路是可靠的。
        viewModelScope.launch {
            graph.pickBus.result.collect { ref ->
                if (ref == null) return@collect
                applyPick(ref)
                graph.pickBus.consume()
            }
        }
    }

    // ── 表单 ────────────────────────────────────────────────────────────────

    // 注意：不能叫 setName / setWorldSetting / setPlotDirection ——
    // `var x by mutableStateOf()` 会生成私有的 `setX`，与手写方法在 JVM 上签名冲突。
    fun updateName(v: String) {
        name = v
    }

    fun updateWorldSetting(v: String) {
        worldSetting = v
    }

    fun updatePlotDirection(v: String) {
        plotDirection = v
    }

    fun toggleTrope(t: String) {
        tropes = if (t in tropes) tropes - t else tropes + t
    }

    /** 套用剧情走向模板，覆盖世界观与走向草稿。 */
    fun applyTemplate(t: PlotTemplate) {
        selectedTemplate = t
        orientation = t.orientation
        worldSetting = t.worldSetting
        plotDirection = t.plotDirection
        tropes = t.tropes
    }

    // ── 人物草稿 ────────────────────────────────────────────────────────────

    fun beginNewCharacter() {
        val now = System.currentTimeMillis()
        characterDraft = Character(
            id = Ids.new("chr"),
            name = "",
            model = defaultCharacterModel,
            isUser = characters.none { it.isUser },
            createdAt = now,
            updatedAt = now,
        )
    }

    fun beginEditCharacter(c: Character) {
        characterDraft = c
    }

    fun updateDraft(transform: (Character) -> Character) {
        characterDraft = characterDraft?.let(transform)
    }

    fun cancelDraft() {
        characterDraft = null
    }

    /** 保存人物草稿。姓名为空则丢弃。 */
    fun commitDraft() {
        val draft = characterDraft ?: return
        if (draft.name.isBlank()) {
            characterDraft = null
            return
        }
        val saved = draft.copy(updatedAt = System.currentTimeMillis())
        characters = if (characters.any { it.id == saved.id }) {
            characters.map { if (it.id == saved.id) saved else it }
        } else {
            characters + saved
        }
        characterDraft = null
    }

    fun removeCharacter(c: Character) {
        characters = characters - c
    }

    fun isEditingExistingDraft(): Boolean =
        characterDraft?.let { d -> characters.any { it.id == d.id } } == true

    // ── 选模型 ──────────────────────────────────────────────────────────────

    /** 由界面调用：登记意图并跳转到选择页。 */
    fun requestPick(target: CreatePickTarget) {
        pendingTarget = target
        graph.pickBus.start(target.kind, target.title, target.subtitle)
    }

    private fun applyPick(ref: ModelRef) {
        when (pendingTarget) {
            CreatePickTarget.NARRATOR -> narratorModel = ref
            CreatePickTarget.DEFAULT_CHARACTER -> defaultCharacterModel = ref
            CreatePickTarget.IMAGE -> imageModel = ref
            CreatePickTarget.CHARACTER_DRAFT -> {
                characterDraft = characterDraft?.copy(model = ref)
            }
            null -> Unit
        }
        pendingTarget = null
    }

    // ── 落盘 ────────────────────────────────────────────────────────────────

    val canCreate: Boolean get() = name.isNotBlank() && worldSetting.isNotBlank() && !creating

    fun create(onCreated: (String) -> Unit) {
        if (!canCreate) return
        creating = true
        error = ""
        viewModelScope.launch {
            runCatching {
                val society = Society(
                    id = Ids.new("soc"),
                    name = name.trim(),
                    worldSetting = worldSetting.trim(),
                    orientation = orientation,
                    plotDirection = plotDirection.trim(),
                    tropes = tropes,
                    coverSeed = Ids.stableSeed(name + System.currentTimeMillis()),
                    narratorModel = narratorModel,
                    defaultCharacterModel = defaultCharacterModel,
                    imageModel = imageModel,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                )
                graph.store.saveSociety(society)
                if (characters.isNotEmpty()) {
                    graph.store.saveCharacters(society.id, characters)
                }
                graph.store.savePlot(
                    society.id,
                    PlotState(
                        act = 1,
                        title = "序章",
                        summary = "社会刚刚建立，一切尚未开始。",
                    ),
                )
                society.id
            }.onSuccess { id ->
                creating = false
                onCreated(id)
            }.onFailure { e ->
                creating = false
                error = "创建失败：${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    companion object {
        fun factory() = viewModelFactory { initializer { CreateViewModel() } }
    }
}

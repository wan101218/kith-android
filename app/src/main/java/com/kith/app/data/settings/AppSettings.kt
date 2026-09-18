package com.kith.app.data.settings

import android.content.Context
import com.kith.app.core.b
import com.kith.app.core.d
import com.kith.app.core.i
import com.kith.app.core.jObj
import com.kith.app.core.l
import com.kith.app.core.s
import com.kith.app.core.strList
import com.kith.app.domain.AiEndpoint
import com.kith.app.domain.EndpointKind
import com.kith.app.domain.ModelRef
import com.kith.app.domain.toAiEndpoint
import com.kith.app.domain.toJson
import com.kith.app.domain.toModelRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** 关系图默认布局。 */
enum class GraphLayout(val label: String) {
    TREE("树状图"),
    CHAIN("链式图");

    companion object {
        fun of(raw: String?): GraphLayout = entries.firstOrNull { it.name == raw } ?: TREE
    }
}

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色");

    companion object {
        fun of(raw: String?): ThemeMode = entries.firstOrNull { it.name == raw } ?: SYSTEM
    }
}

/**
 * 全局设置快照。UI 直接订阅它。
 *
 * 这里放的是**跨社会共享**的东西：接入点与密钥、已保存的模型配置、视觉桥接、
 * 界面偏好。社会自身的内容（世界观、人物、关系、剧情、聊天）全部在
 * [com.kith.app.data.store.SocietyStore] 里按社会分文件夹存放，两边刻意不混。
 */
data class SettingsSnapshot(
    val endpoints: List<AiEndpoint> = emptyList(),
    /** 已保存、可跨社会复用的模型配置 */
    val savedModels: List<ModelRef> = emptyList(),

    // 视觉桥接：给纯文本模型补上「看图」能力
    val visionEnabled: Boolean = true,
    val visionBaseUrl: String = DEFAULT_VISION_BASE,
    val visionApiKey: String = DEFAULT_VISION_KEY,
    val visionModel: String = DEFAULT_VISION_MODEL,
    val visionNoticeAccepted: Boolean = false,

    /** 旁白自动为新 NPC 分配模型档位 */
    val narratorAutoAssignModel: Boolean = true,
    /** 旁白自动生成 NPC 时，是否允许直接写入关系链 */
    val narratorAutoLinkRelations: Boolean = true,

    /** 上帝视角：是否允许查看 NPC 之间的对话 */
    val godViewEnabled: Boolean = true,

    val graphLayout: GraphLayout = GraphLayout.TREE,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,

    /** 目录刷新的地址与时间 */
    val catalogUrl: String = "",
    val catalogRefreshedAt: Long = 0L,

    /**
     * 美元 → 人民币汇率。
     *
     * 金额内部一律按美元记账（模型目录的价格源就是美元），只在显示时换算。
     * 汇率做成可调是因为各人拿到的报价不一样 —— 走中转站的、有企业折扣的、
     * 或者按银行现汇价算的，都会和 7.2 有出入。改这里全局立即生效。
     */
    val usdToCnyRate: Double = 7.2,

    /** 每次进入社会是否自动让旁白检查剧情推进 */
    val autoNarratorOnEnter: Boolean = false,

    // ── 本地内容审核（Qwen3Guard 0.6B）────────────────────────────────────
    /** 默认关闭；开启需要用户在弹窗里确认内存代价 */
    val guardEnabled: Boolean = false,
    /** 「性能不达标已关闭审核」的启动提示只弹一次 */
    val guardUnderSpecNoticeShown: Boolean = false,
) {
    fun endpoint(id: String): AiEndpoint? = endpoints.firstOrNull { it.id == id }

    /** 解析一个 ModelRef 对应的接入点；接入点被删掉时返回 null。 */
    fun resolve(ref: ModelRef?): Pair<AiEndpoint, ModelRef>? {
        if (ref == null) return null
        val ep = endpoint(ref.endpointId) ?: return null
        return ep to ref
    }

    companion object {
        /**
         * 视觉桥接的默认配置。
         *
         * **地址预置、Key 一律不预置。** 项目源码里曾经内置过一个共享 Key，
         * 但那意味着任何拿到 APK 的人都在共用同一个凭据（额度会被陌生人消耗，
         * 且随时可能因滥用失效）。所以这里只留厂商地址，Key 由用户自己填 ——
         * 首次真正触发视觉调用时应用会提示一次。
         */
        const val DEFAULT_VISION_BASE = "https://open.bigmodel.cn/api/paas/v4"
        const val DEFAULT_VISION_KEY = ""
        const val DEFAULT_VISION_MODEL = "glm-4v-flash"
    }
}

/**
 * 设置的持久化。
 *
 * 用 SharedPreferences 而不是 DataStore：这些设置都是小体量的键值，
 * 读写同步即可，且不需要额外的协程初始化流程，能让设置页立刻可用。
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("kith_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsSnapshot> = _state.asStateFlow()

    val current: SettingsSnapshot get() = _state.value

    init {
        seedBuiltinEndpoints()
        purgeBuiltinKeys()
    }

    /**
     * 预置内置接入点：只预置**地址**，Key 一律留空由用户自己填。
     *
     * 源码不携带任何可用凭据 —— 随 APK 分发的 Key 等于公开共享，额度会被
     * 陌生人消耗，也随时可能因滥用失效。
     *
     * 只在首次启动（打点不存在）时执行一次：写入后即落标记，之后用户删掉
     * 该接入点也不会再自动恢复 —— 自动「复活」用户明确删除的东西是反直觉的。
     */
    private fun seedBuiltinEndpoints() {
        if (prefs.getBoolean(KEY_BUILTIN_SEEDED, false)) return
        prefs.edit().putBoolean(KEY_BUILTIN_SEEDED, true).apply()
        if (current.endpoint(BUILTIN_YUNZHOU_ENDPOINT_ID) == null) {
            upsertEndpoint(
                AiEndpoint(
                    id = BUILTIN_YUNZHOU_ENDPOINT_ID,
                    label = "云舟API（内置，需自填 Key）",
                    vendor = "Yunzhou",
                    kind = EndpointKind.IMAGE,
                    baseUrl = BUILTIN_YUNZHOU_BASE,
                    apiKey = "",
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /**
     * 清掉随 APK 分发过的内置 Key。
     *
     * 老版本曾把站点公共令牌写进源码并在启动时 seed 进设置。这里把内部接入点
     * 的 Key 一律置空 —— 用户自己填的凭据不受影响（只有这个内置 id 会被处理）。
     */
    private fun purgeBuiltinKeys() {
        val ep = current.endpoint(BUILTIN_YUNZHOU_ENDPOINT_ID) ?: return
        if (ep.apiKey.isEmpty()) return
        upsertEndpoint(ep.copy(apiKey = ""))
    }

    // ── 读取 ────────────────────────────────────────────────────────────────

    private fun read(): SettingsSnapshot {
        val raw = prefs.getString(KEY_SNAPSHOT, null)
        val base = SettingsSnapshot()
        if (raw.isNullOrEmpty()) return base
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return base
        return SettingsSnapshot(
            endpoints = o.optJSONArray("endpoints").toEndpointList(),
            savedModels = o.optJSONArray("savedModels").toModelRefList(),
            visionEnabled = o.b("visionEnabled", base.visionEnabled),
            visionBaseUrl = o.s("visionBaseUrl", base.visionBaseUrl),
            visionApiKey = o.s("visionApiKey", base.visionApiKey),
            visionModel = o.s("visionModel", base.visionModel),
            visionNoticeAccepted = o.b("visionNoticeAccepted"),
            narratorAutoAssignModel = o.b("narratorAutoAssignModel", base.narratorAutoAssignModel),
            narratorAutoLinkRelations = o.b("narratorAutoLinkRelations", base.narratorAutoLinkRelations),
            godViewEnabled = o.b("godViewEnabled", base.godViewEnabled),
            graphLayout = GraphLayout.of(o.s("graphLayout")),
            themeMode = ThemeMode.of(o.s("themeMode")),
            catalogUrl = o.s("catalogUrl"),
            catalogRefreshedAt = o.l("catalogRefreshedAt"),
            usdToCnyRate = o.d("usdToCnyRate", base.usdToCnyRate),
            autoNarratorOnEnter = o.b("autoNarratorOnEnter"),
            guardEnabled = o.b("guardEnabled"),
            guardUnderSpecNoticeShown = o.b("guardUnderSpecNoticeShown"),
        )
    }

    private fun write(s: SettingsSnapshot) {
        val o = jObj(
            "endpoints" to JSONArray().also { a -> s.endpoints.forEach { a.put(it.toJson()) } },
            "savedModels" to JSONArray().also { a -> s.savedModels.forEach { a.put(it.toJson()) } },
            "visionEnabled" to s.visionEnabled,
            "visionBaseUrl" to s.visionBaseUrl,
            "visionApiKey" to s.visionApiKey,
            "visionModel" to s.visionModel,
            "visionNoticeAccepted" to s.visionNoticeAccepted,
            "narratorAutoAssignModel" to s.narratorAutoAssignModel,
            "narratorAutoLinkRelations" to s.narratorAutoLinkRelations,
            "godViewEnabled" to s.godViewEnabled,
            "graphLayout" to s.graphLayout.name,
            "themeMode" to s.themeMode.name,
            "catalogUrl" to s.catalogUrl,
            "catalogRefreshedAt" to s.catalogRefreshedAt,
            "usdToCnyRate" to s.usdToCnyRate,
            "autoNarratorOnEnter" to s.autoNarratorOnEnter,
            "guardEnabled" to s.guardEnabled,
            "guardUnderSpecNoticeShown" to s.guardUnderSpecNoticeShown,
        )
        prefs.edit().putString(KEY_SNAPSHOT, o.toString()).apply()
        _state.value = s
    }

    private fun update(block: (SettingsSnapshot) -> SettingsSnapshot) {
        write(block(_state.value))
    }

    // ── 接入点 ──────────────────────────────────────────────────────────────

    fun upsertEndpoint(endpoint: AiEndpoint) = update { s ->
        val list = s.endpoints.toMutableList()
        val idx = list.indexOfFirst { it.id == endpoint.id }
        if (idx >= 0) list[idx] = endpoint else list.add(endpoint)
        s.copy(endpoints = list)
    }

    fun removeEndpoint(id: String) = update { s ->
        s.copy(
            endpoints = s.endpoints.filterNot { it.id == id },
            // 接入点没了，挂在它下面的模型配置也一并清掉，避免留下点了就报错的死引用
            savedModels = s.savedModels.filterNot { it.endpointId == id },
        )
    }

    // ── 已保存的模型配置 ────────────────────────────────────────────────────

    fun saveModel(ref: ModelRef) = update { s ->
        val list = s.savedModels.toMutableList()
        val idx = list.indexOfFirst { it.endpointId == ref.endpointId && it.modelId == ref.modelId }
        if (idx >= 0) list[idx] = ref else list.add(ref)
        s.copy(savedModels = list)
    }

    fun removeSavedModel(ref: ModelRef) = update { s ->
        s.copy(
            savedModels = s.savedModels.filterNot {
                it.endpointId == ref.endpointId && it.modelId == ref.modelId
            },
        )
    }

    // ── 视觉桥接 ────────────────────────────────────────────────────────────

    fun updateVision(
        enabled: Boolean = current.visionEnabled,
        baseUrl: String = current.visionBaseUrl,
        apiKey: String = current.visionApiKey,
        model: String = current.visionModel,
    ) = update {
        it.copy(
            visionEnabled = enabled,
            visionBaseUrl = baseUrl.trim().trimEnd('/'),
            visionApiKey = apiKey.trim(),
            visionModel = model.trim(),
        )
    }

    fun acceptVisionNotice() = update { it.copy(visionNoticeAccepted = true) }

    /**
     * 恢复视觉桥接到内置默认值（只有厂商地址与模型名；Key 不在其中 ——
     * 项目不再内置任何共享额度，恢复默认即等于「清空 Key，请自己填」）。
     */
    fun resetVision() = update {
        it.copy(
            visionEnabled = true,
            visionBaseUrl = SettingsSnapshot.DEFAULT_VISION_BASE,
            visionApiKey = SettingsSnapshot.DEFAULT_VISION_KEY,
            visionModel = SettingsSnapshot.DEFAULT_VISION_MODEL,
        )
    }

    /** 视觉桥接还没填 Key —— 没有内置额度可兜底，未填就是调不通。 */
    fun visionKeyMissing(): Boolean = current.visionApiKey.isBlank()

    // ── 行为开关 ────────────────────────────────────────────────────────────

    fun updateNarrator(autoAssignModel: Boolean, autoLinkRelations: Boolean) = update {
        it.copy(
            narratorAutoAssignModel = autoAssignModel,
            narratorAutoLinkRelations = autoLinkRelations,
        )
    }

    fun setGodView(enabled: Boolean) = update { it.copy(godViewEnabled = enabled) }

    /** 改汇率。Money 通过 rateProvider 直接读设置，所以改完立即全局生效。 */
    fun setUsdToCnyRate(rate: Double) =
        update { it.copy(usdToCnyRate = if (rate.isFinite() && rate > 0) rate else it.usdToCnyRate) }

    fun setAutoNarratorOnEnter(enabled: Boolean) = update { it.copy(autoNarratorOnEnter = enabled) }

    // ── 本地内容审核 ────────────────────────────────────────────────────────

    /** 开关由设置页弹窗确认后调用；关闭时应用侧还要释放推理引擎的内存。 */
    fun setGuardEnabled(enabled: Boolean) = update { it.copy(guardEnabled = enabled) }

    /** 启动提示只弹一次。 */
    fun markGuardUnderSpecNoticeShown() =
        update { it.copy(guardUnderSpecNoticeShown = true) }

    fun setGraphLayout(layout: GraphLayout) = update { it.copy(graphLayout = layout) }

    fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }

    fun setCatalogRefresh(url: String) = update { it.copy(catalogUrl = url) }

    fun markCatalogRefreshed(at: Long = System.currentTimeMillis()) =
        update { it.copy(catalogRefreshedAt = at) }

    // ── 编解码小工具 ────────────────────────────────────────────────────────

    private fun JSONArray?.toEndpointList(): List<AiEndpoint> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it)?.toAiEndpoint() }
    }

    private fun JSONArray?.toModelRefList(): List<ModelRef> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it)?.toModelRef() }
    }

    companion object {
        private const val KEY_SNAPSHOT = "snapshot_v1"
        /** v2：内置接入点改为「只预置地址、不预置 Key」，老设备需要重跑一次。 */
        private const val KEY_BUILTIN_SEEDED = "builtin_endpoints_seeded_v2"

        /**
         * 内置云舟生图接入点（站点 cli.999554.xyz，new-api 面板）。
         *
         * 只预置地址，Key 留空 —— 站点令牌属于公共凭据，写进源码等于公开分发。
         * 用户在设置里填自己的 Key 即可启用。
         */
        const val BUILTIN_YUNZHOU_ENDPOINT_ID = "builtin_yunzhou_image"
        const val BUILTIN_YUNZHOU_BASE = "https://cli.999554.xyz/v1"
    }
}

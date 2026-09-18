package com.kith.app.data.catalog

import android.content.Context
import com.kith.app.ai.Http
import com.kith.app.core.i
import com.kith.app.core.jObj
import com.kith.app.core.l
import com.kith.app.core.obj
import com.kith.app.core.s
import com.kith.app.core.so
import com.kith.app.core.strList
import com.kith.app.domain.ModelTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 目录中的一条模型记录。
 *
 * 数据来源是 AIfanfiction 工程里的 `modelwatch_export.py`：它把 OpenRouter 与
 * Ollama Library 两个源归一化成统一结构，价格单位是**美元 / 百万 token**。
 */
data class CatalogModel(
    val id: String,
    val name: String,
    val vendorKey: String,
    val vendor: VendorInfo,
    val contextLength: Int,
    /** 输入模态，含 "image" 即支持读图 */
    val modalities: List<String>,
    /** 输入单价（美元/百万 token）。null 表示该源未提供价格 */
    val promptPerM: Double?,
    /** 输出单价（美元/百万 token） */
    val completionPerM: Double?,
    val releasedTs: Long,
    val expirationTs: Long?,
    val sourceKey: String,
    val url: String,
) {
    /** 支持读图 —— 决定了纯文本模型是否需要走视觉桥接。 */
    val supportsVision: Boolean
        get() = modalities.any { it == "image" || it == "video" }

    /** OpenRouter 的 `:free` 变体，价格字段为空。 */
    val isFree: Boolean
        get() = id.contains(":free")

    val priceKnown: Boolean
        get() = promptPerM != null || completionPerM != null

    /** 综合单价，用于排序与档位划分。 */
    val blendedPrice: Double
        get() = (promptPerM ?: 0.0) + (completionPerM ?: 0.0)

    /** 是否已标注即将下线。 */
    fun expiringSoon(now: Long = System.currentTimeMillis()): Boolean {
        val exp = expirationTs ?: return false
        return exp > now && exp - now < 30L * 86_400_000L
    }

    val isExpired: Boolean
        get() = expirationTs?.let { it in 1 until System.currentTimeMillis() } ?: false
}

data class CatalogSnapshot(
    val models: List<CatalogModel>,
    val generatedAt: String = "",
    /** 当前快照的来源描述，用于在设置页展示 */
    val origin: String = "内置快照",
) {
    val freeCount: Int get() = models.count { it.isFree }
    val visionCount: Int get() = models.count { it.supportsVision }
}

/** 列表页的筛选条件。 */
data class CatalogQuery(
    val keyword: String = "",
    val vendors: Set<String> = emptySet(),
    val onlyFree: Boolean = false,
    val onlyVision: Boolean = false,
    val onlyTextOnly: Boolean = false,
    val maxBlendedPrice: Double? = null,
    val sort: CatalogSort = CatalogSort.PRICE_ASC,
)

enum class CatalogSort(val label: String) {
    PRICE_ASC("价格从低到高"),
    PRICE_DESC("价格从高到低"),
    NEWEST("最新发布"),
    CONTEXT("上下文长度"),
    NAME("名称"),
}

/**
 * 模型目录。
 *
 * 三层数据，优先级从高到低：
 *   1. 用户在线刷新 / 手动导入后的缓存副本（filesDir/catalog/）
 *   2. App 内置的 assets 快照（随版本发布）
 *   3. 用户手工添加的自定义模型（存在设置里，不在这里）
 *
 * 「价格」这个维度是整个产品的关键输入：旁白要给新生成的 NPC 自动分配模型，
 * 判据就是「这个角色有多重要」×「这个模型多少钱」。
 */
class ModelCatalog(private val context: Context) {

    private val lock = Mutex()
    private var snapshot: CatalogSnapshot? = null

    private val cacheFile: File
        get() = File(context.filesDir, "catalog/model_catalog.json")

    private var index: Map<String, CatalogModel> = emptyMap()

    /** 载入目录。首次调用会读盘，之后走内存缓存。 */
    suspend fun ensureLoaded(): CatalogSnapshot = lock.withLock {
        snapshot?.let { return it }
        val loaded = withContext(Dispatchers.IO) { readBestSource() }
        index = loaded.models.associateBy { it.id }
        snapshot = loaded
        loaded
    }

    suspend fun current(): CatalogSnapshot = ensureLoaded()

    fun find(modelId: String): CatalogModel? = index[modelId]

    /** 展示用的模型名。目录里查不到就退回 id —— 用户自定义模型也能正常显示。 */
    fun displayName(modelId: String): String =
        index[modelId]?.name ?: modelId

    // ── 读取 ────────────────────────────────────────────────────────────────

    private fun readBestSource(): CatalogSnapshot {
        if (cacheFile.exists()) {
            runCatching { parse(cacheFile.readText(), "在线刷新") }
                .getOrNull()
                ?.takeIf { it.models.isNotEmpty() }
                ?.let { return it }
        }
        val bundled = runCatching {
            context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        }.getOrNull()
        if (bundled != null) {
            runCatching { parse(bundled, "内置快照") }
                .getOrNull()
                ?.takeIf { it.models.isNotEmpty() }
                ?.let { return it }
        }
        return CatalogSnapshot(emptyList(), origin = "无可用目录")
    }

    private fun parse(raw: String, origin: String): CatalogSnapshot {
        val root = JSONObject(raw)
        val arr = root.optJSONArray("models") ?: return CatalogSnapshot(emptyList())
        val models = ArrayList<CatalogModel>(arr.length())
        for (idx in 0 until arr.length()) {
            val o = arr.optJSONObject(idx) ?: continue
            val id = o.s("id").ifEmpty { continue }
            val vendorRaw = o.s("vendor")
            val pricing = o.obj("pricing")
            models += CatalogModel(
                id = id,
                name = o.s("name").ifEmpty { id },
                vendorKey = VendorRegistry.normalize(vendorRaw) ?: vendorRaw.ifEmpty { "Unknown" },
                vendor = VendorRegistry.of(vendorRaw),
                contextLength = o.i("context_length"),
                modalities = o.strList("modalities"),
                promptPerM = if (pricing.has("prompt_per_m")) pricing.let { p ->
                    if (p.isNull("prompt_per_m")) null else p.getDouble("prompt_per_m")
                } else null,
                completionPerM = if (pricing.has("completion_per_m")) pricing.let { p ->
                    if (p.isNull("completion_per_m")) null else p.getDouble("completion_per_m")
                } else null,
                releasedTs = o.l("released_ts"),
                expirationTs = if (o.isNull("expiration_ts")) null else o.l("expiration_ts"),
                sourceKey = o.s("source_key"),
                url = o.s("url"),
            )
        }
        return CatalogSnapshot(
            models = models,
            generatedAt = root.s("generated_at"),
            origin = origin,
        )
    }

    // ── 刷新与导入 ──────────────────────────────────────────────────────────

    /**
     * 从 URL 拉取最新目录。
     *
     * 典型用法：在电脑上跑 `python modelwatch_export.py --serve 8099`，
     * 然后把 `http://<电脑局域网IP>:8099/models.json` 填进来。
     */
    suspend fun refreshFromUrl(url: String): Result<CatalogSnapshot> = runCatching {
        val result = Http.getText(url)
        if (!result.ok) error("HTTP ${result.code}")
        acceptNewSnapshot(result.body, "在线刷新 $url")
    }

    /** 用户手动粘贴 JSON 导入。 */
    suspend fun importFromJson(raw: String): Result<CatalogSnapshot> = runCatching {
        acceptNewSnapshot(raw, "手动导入")
    }

    private suspend fun acceptNewSnapshot(raw: String, origin: String): CatalogSnapshot {
        val parsed = parse(raw, origin)
        require(parsed.models.isNotEmpty()) { "解析后没有任何模型，请确认是 modelwatch 导出的 JSON" }
        return lock.withLock {
            withContext(Dispatchers.IO) {
                cacheFile.parentFile?.mkdirs()
                cacheFile.writeText(raw)
            }
            index = parsed.models.associateBy { it.id }
            snapshot = parsed
            parsed
        }
    }

    /** 丢弃刷新缓存，回到内置快照。 */
    suspend fun resetToBundled(): CatalogSnapshot = lock.withLock {
        withContext(Dispatchers.IO) { if (cacheFile.exists()) cacheFile.delete() }
        snapshot = null
        index = emptyMap()
        ensureLoaded()
    }

    /** 导出当前目录的原始 JSON，便于分享给其他设备。 */
    suspend fun exportJson(): String {
        val cached = cacheFile.takeIf { it.exists() }?.readText()
        if (cached != null) return cached
        return withContext(Dispatchers.IO) {
            context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        }
    }

    // ── 价格与档位 ──────────────────────────────────────────────────────────

    /**
     * 估算一次调用的花费（美元）。
     * 未提供价格的模型返回 0，并在 UI 上标注「价格未知」而不是假装免费。
     */
    fun estimateCostUsd(modelId: String, tokensIn: Int, tokensOut: Int): Double {
        val m = index[modelId] ?: return 0.0
        val inCost = (m.promptPerM ?: 0.0) * tokensIn / 1_000_000.0
        val outCost = (m.completionPerM ?: 0.0) * tokensOut / 1_000_000.0
        return inCost + outCost
    }

    /**
     * 按档位挑选候选模型。
     *
     * 判据是价格分位，而不是硬编码的型号名单 —— 这样目录一刷新，档位划分自动跟着变，
     * 不会出现「名单里的模型下线了但代码还指着它」的情况。
     *
     * @param requireVision 是否必须支持读图
     * @param vendorKey 限定厂商；null 表示不限
     */
    suspend fun candidatesForTier(
        tier: ModelTier,
        requireVision: Boolean = false,
        vendorKey: String? = null,
    ): List<CatalogModel> {
        val all = ensureLoaded().models
        val pool = all.asSequence()
            .filter { it.priceKnown || it.isFree }
            .filter { !it.isExpired }
            .filter { !requireVision || it.supportsVision }
            .filter { vendorKey == null || it.vendorKey == vendorKey }
            .toList()

        if (pool.isEmpty()) {
            // 没有任何带价格的模型时，退回到「有价格的」全集再放宽一次
            return all.filter { !requireVision || it.supportsVision }.take(20)
        }

        val sorted = pool.sortedBy { it.blendedPrice }
        val n = sorted.size
        val (lo, hi) = when (tier) {
            ModelTier.ECONOMY -> 0 to (n / 3).coerceAtLeast(1)
            ModelTier.STANDARD -> (n / 3) to (2 * n / 3).coerceAtLeast(n / 3 + 1)
            ModelTier.PREMIUM -> (2 * n / 3) to n
        }
        return sorted.subList(lo.coerceIn(0, n), hi.coerceIn(0, n))
    }

    /** 按档位挑一个具体模型。同级内优先选上下文更长的。 */
    suspend fun pickForTier(
        tier: ModelTier,
        requireVision: Boolean = false,
        vendorKey: String? = null,
    ): CatalogModel? = candidatesForTier(tier, requireVision, vendorKey)
        .maxByOrNull { it.contextLength }

    // ── 筛选 ────────────────────────────────────────────────────────────────

    suspend fun query(q: CatalogQuery): List<CatalogModel> {
        val all = ensureLoaded().models
        val kw = q.keyword.trim().lowercase()

        val filtered = all.filter { m ->
            (kw.isEmpty() ||
                m.name.lowercase().contains(kw) ||
                m.id.lowercase().contains(kw) ||
                m.vendor.name.lowercase().contains(kw)) &&
                (q.vendors.isEmpty() || m.vendorKey in q.vendors) &&
                (!q.onlyFree || m.isFree) &&
                (!q.onlyVision || m.supportsVision) &&
                (!q.onlyTextOnly || !m.supportsVision) &&
                (q.maxBlendedPrice == null || m.blendedPrice <= q.maxBlendedPrice)
        }

        return when (q.sort) {
            CatalogSort.PRICE_ASC -> filtered.sortedBy { it.blendedPrice }
            CatalogSort.PRICE_DESC -> filtered.sortedByDescending { it.blendedPrice }
            CatalogSort.NEWEST -> filtered.sortedByDescending { it.releasedTs }
            CatalogSort.CONTEXT -> filtered.sortedByDescending { it.contextLength }
            CatalogSort.NAME -> filtered.sortedBy { it.name.lowercase() }
        }
    }

    /** 当前快照里出现过的厂商，按模型数量降序，用于筛选面板。 */
    suspend fun vendorsInUse(): List<Pair<VendorInfo, Int>> {
        val all = ensureLoaded().models
        return all.groupBy { it.vendorKey }
            .mapNotNull { (key, list) ->
                list.firstOrNull()?.let { it.vendor to list.size }
            }
            .sortedByDescending { it.second }
    }

    companion object {
        const val ASSET_NAME = "model_catalog.json"
    }
}

package com.kith.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kith.app.ai.engine.Money
import com.kith.app.data.catalog.CatalogModel
import com.kith.app.data.catalog.ImageModelPresets
import com.kith.app.data.catalog.VendorRegistry
import com.kith.app.domain.AiEndpoint
import com.kith.app.domain.EndpointKind
import com.kith.app.domain.ModelRef
import com.kith.app.kithGraph
import com.kith.app.ui.common.GlassTopBar
import com.kith.app.ui.common.KithIcons
import com.kith.app.ui.common.KithIcon
import com.kith.app.ui.common.Pill
import com.kith.app.ui.common.StatItem
import com.kith.app.ui.common.VendorMark

/** 模型列表的排序方式。 */
private enum class SortMode(val label: String) {
    PRICE_ASC("价格从低到高"),
    PRICE_DESC("价格从高到低"),
    NEWEST("最新发布"),
    CONTEXT("上下文最长"),
}

/** 模型列表的筛选条件。 */
private enum class VisionFilter(val label: String) {
    ALL("全部"),
    VISION("能读图"),
    TEXT_ONLY("纯文本"),
    FREE("免费"),
}

/**
 * 选模型 · 第二步：在该接入点所属厂商下挑一个具体模型。
 *
 * 这一页是「单开一页」收益最大的地方。列表最多 371 条，需要搜索、按能力筛选、
 * 按价格排序，还要能一眼看到单价与上下文长度 —— 半屏的底部面板做不了这些。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelListScreen(
    endpoint: AiEndpoint,
    onPicked: (ModelRef) -> Unit,
    onBack: () -> Unit,
) {
    val graph = kithGraph
    val vendor = VendorRegistry.of(endpoint.vendor)

    var keyword by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SortMode.PRICE_ASC) }
    var filter by remember { mutableStateOf(VisionFilter.ALL) }
    var manual by remember { mutableStateOf("") }

    var catalogModels by remember { mutableStateOf<List<CatalogModel>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    // 生图走人工维护的预设表：目录的 modalities 描述的是**输入**模态，
    // 用它筛生图模型会把一堆视觉理解的 LLM 误判成画图模型。
    val isImage = endpoint.kind == EndpointKind.IMAGE

    LaunchedEffect(endpoint.id) {
        loading = true
        catalogModels = if (isImage) {
            emptyList()
        } else {
            graph.catalog.query(
                com.kith.app.data.catalog.CatalogQuery(
                    vendors = setOf(endpoint.vendor),
                    sort = com.kith.app.data.catalog.CatalogSort.PRICE_ASC,
                ),
            )
        }
        loading = false
    }

    // 接入点真实支持的模型列表（OpenAI 兼容的 GET /models）。
    // 内置目录是 OpenRouter 语料，模型名与第三方接入点大概率对不上 ——
    // 这里拿到的才是点选后立刻能用的名字。拉取失败就不显示该区域。
    var endpointModels by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(endpoint.id) {
        if (isImage) return@LaunchedEffect
        endpointModels = graph.llm.fetchEndpointModels(endpoint).getOrNull()
    }

    val imagePresets = if (isImage) {
        ImageModelPresets.all.filter { it.vendorKey == endpoint.vendor }
            .ifEmpty { ImageModelPresets.all }
    } else {
        emptyList()
    }

    val shown: List<CatalogModel> = remember(catalogModels, keyword, sort, filter) {
        catalogModels
            .filter { m ->
                (keyword.isBlank() ||
                    m.name.contains(keyword, true) ||
                    m.id.contains(keyword, true)) &&
                    when (filter) {
                        VisionFilter.ALL -> true
                        VisionFilter.VISION -> m.supportsVision
                        VisionFilter.TEXT_ONLY -> !m.supportsVision
                        VisionFilter.FREE -> m.isFree
                    }
            }
            .let { list ->
                when (sort) {
                    SortMode.PRICE_ASC -> list.sortedBy { it.blendedPrice }
                    SortMode.PRICE_DESC -> list.sortedByDescending { it.blendedPrice }
                    SortMode.NEWEST -> list.sortedByDescending { it.releasedTs }
                    SortMode.CONTEXT -> list.sortedByDescending { it.contextLength }
                }
            }
    }

    val freeCount = catalogModels.count { it.isFree }
    val visionCount = catalogModels.count { it.supportsVision }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize()) {
            GlassTopBar(
                title = vendor.name,
                subtitle = endpoint.label,
                onBack = onBack,
                statusBarPadding = padding.calculateTopPadding() / 2,
                stats = if (isImage) listOf(
                    StatItem("预设模型", imagePresets.size.toString()),
                ) else listOf(
                    StatItem("可用模型", catalogModels.size.toString()),
                    StatItem("免费", freeCount.toString()),
                    StatItem("能读图", visionCount.toString()),
                ),
            )

            if (isImage) {
                ImagePresetList(
                    presets = imagePresets,
                    endpoint = endpoint,
                    manual = manual,
                    onManual = { manual = it },
                    onPicked = onPicked,
                    contentPadding = padding.calculateBottomPadding(),
                )
                return@Column
            }

            // ── 搜索 + 筛选 + 排序 ──
            Column(Modifier.padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    placeholder = { Text("搜索模型名或 id") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    VisionFilter.entries.forEach { f ->
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = { Text(f.label) },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    SortMode.entries.forEach { s ->
                        FilterChip(
                            selected = sort == s,
                            onClick = { sort = s },
                            label = { Text(s.label) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // ── 列表 ──
            if (shown.isEmpty() && !loading) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (catalogModels.isEmpty()) {
                            "内置目录里没有「${vendor.name}」的模型。\n" +
                                "这类接入点（自建中转、私有部署）直接在下面手填模型名即可。"
                        } else {
                            "没有符合条件的模型，换个筛选条件试试。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    // ── 此接入点支持的模型（真实列表，点选即用）──
                    val epIds = endpointModels
                    if (epIds != null && epIds.isNotEmpty()) {
                        item(key = "ep-models-header") {
                            Text(
                                "此接入点支持的模型（点选即用）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(epIds, key = { "ep:$it" }) { id ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onPicked(
                                            ModelRef(endpoint.id, id, id, endpoint.vendor),
                                        )
                                    }
                                    .padding(horizontal = 16.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                KithIcon(
                                    KithIcons.Chip,
                                    size = 17.dp,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    id,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                        }
                        item(key = "ep-models-warning") {
                            Text(
                                "⚠ 下方目录的模型名与上面不是一套命名，选了大概率报 400，优先从上面选。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                    }

                    items(shown, key = { it.id }) { m ->
                        ModelRow(m) {
                            onPicked(
                                ModelRef(
                                    endpointId = endpoint.id,
                                    modelId = m.id,
                                    label = m.name,
                                    vendor = endpoint.vendor,
                                ),
                            )
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }

            // ── 手填兜底 ──
            ManualModelInput(
                manual = manual,
                onManual = { manual = it },
                onUse = { onPicked(ModelRef(endpoint.id, it, it, endpoint.vendor)) },
                bottomPadding = padding.calculateBottomPadding(),
            )
        }
    }
}

@Composable
private fun ModelRow(m: CatalogModel, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VendorMark(m.vendor, size = 30.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    m.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (m.isFree) {
                    Spacer(Modifier.width(6.dp))
                    Pill("免费", MaterialTheme.colorScheme.tertiary)
                }
                if (m.supportsVision) {
                    Spacer(Modifier.width(5.dp))
                    KithIcon(
                        KithIcons.Vision,
                        size = 14.dp,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (m.expiringSoon()) {
                    Spacer(Modifier.width(5.dp))
                    Pill("将下线", MaterialTheme.colorScheme.error)
                }
            }
            Text(
                m.id,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                Money.perMillion(m.promptPerM, m.completionPerM) +
                    if (m.contextLength > 0) " · 上下文 ${Money.tokenCount(m.contextLength)}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ColumnScope.ImagePresetList(
    presets: List<com.kith.app.data.catalog.ImageModelPreset>,
    endpoint: AiEndpoint,
    manual: String,
    onManual: (String) -> Unit,
    onPicked: (ModelRef) -> Unit,
    contentPadding: androidx.compose.ui.unit.Dp,
) {
    LazyColumn(Modifier.weight(1f)) {
        items(presets, key = { it.modelId }) { p ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        onPicked(
                            ModelRef(
                                endpointId = endpoint.id,
                                modelId = p.modelId,
                                label = p.name,
                                vendor = p.vendorKey,
                            ),
                        )
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VendorMark(p.vendor, size = 30.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            p.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        if (p.free) {
                            Spacer(Modifier.width(6.dp))
                            Pill("免费", MaterialTheme.colorScheme.tertiary)
                        }
                    }
                    Text(
                        p.note.ifEmpty { p.modelId },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                Text(
                    p.priceHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
        }
    }
    ManualModelInput(
        manual = manual,
        onManual = onManual,
        onUse = { onPicked(ModelRef(endpoint.id, it, it, endpoint.vendor)) },
        bottomPadding = contentPadding,
    )
}

@Composable
private fun ManualModelInput(
    manual: String,
    onManual: (String) -> Unit,
    onUse: (String) -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .padding(bottom = bottomPadding),
        ) {
            Text(
                "目录里没有？直接手填模型名",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = manual,
                    onValueChange = onManual,
                    placeholder = { Text("如 gpt-4o-mini / deepseek-chat") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onUse(manual.trim()) },
                    enabled = manual.isNotBlank(),
                ) { Text("使用") }
            }
        }
    }
}

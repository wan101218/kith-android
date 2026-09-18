package com.kith.app.ui.society

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.draw.blur
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.kith.app.ui.theme.LocalKithColors
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kith.app.ai.engine.Money
import com.kith.app.data.settings.GraphLayout
import com.kith.app.domain.Character
import com.kith.app.domain.EndpointKind
import com.kith.app.domain.Importance
import com.kith.app.domain.ModelRef
import com.kith.app.kithGraph
import com.kith.app.ui.character.CharacterEditorDialog
import com.kith.app.ui.common.CharacterAvatar
import com.kith.app.ui.common.GlassTopBar
import com.kith.app.ui.common.KithCard
import com.kith.app.ui.common.KithIcons
import com.kith.app.ui.common.KithIcon
import com.kith.app.ui.common.Pill
import com.kith.app.ui.common.SectionHeader
import com.kith.app.ui.common.StatItem
import com.kith.app.ui.graph.GraphLayoutResult
import com.kith.app.ui.graph.RelationGraph
import com.kith.app.ui.graph.RelationGraphLayout
import com.kith.app.ui.theme.color
import kotlinx.coroutines.launch

/**
 * 社会主界面。
 *
 * 进来第一眼就是关系图 —— 这是产品定的首要信息。上方是透明状态栏，除了标题还
 * 直接压着这个社会的运行数据（人数、关系数、累计花费、当前章节）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SocietyScreen(
    societyId: String,
    onNavigateToPicker: () -> Unit,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenLogs: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: SocietyViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        key = societyId,
        factory = SocietyViewModel.factory(societyId),
    ),
) {
    val graph = kithGraph
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var peopleMenuOpen by remember { mutableStateOf(false) }

    // 导入人物文件：读取用户选的 JSON 交给 ViewModel 解析
    val charImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val raw = runCatching {
                context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (raw.isNullOrBlank()) snackbar.showSnackbar("读取文件失败")
            else vm.importCharacters(raw)
        }
    }

    var selected by remember { mutableStateOf<Character?>(null) }
    var narratorOpen by remember { mutableStateOf(false) }
    var societyModelOpen by remember { mutableStateOf(false) }

    /** 人物草稿存在 ViewModel 里 —— 跳去选模型再回来，编辑到一半的内容不会丢。 */
    val characterDraft = state.characterDraft

    state.toast.takeIf { it.isNotEmpty() }?.let { msg ->
        LaunchedEffect(msg) {
            snackbar.showSnackbar(msg)
            vm.consumeToast()
        }
    }

    val bundle = state.bundle

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize()) {
            // 紧凑头部：两行搞定，把高度尽量让给关系图。
            // 原来这里是「GlassTopBar（三行）+ 独立的切换行」，一共吃掉约 170dp，
            // 压成两行后只剩约 104dp。
            SocietyHeader(
                title = bundle?.society?.name ?: "载入中",
                orientation = bundle?.society?.orientation?.label.orEmpty(),
                meta = bundle?.let {
                    // 顺序按重要性排：一旦空间不够被省略号截断，丢的是最不关键的信息
                    "${it.characters.size} 人 · ${it.relations.size} 关系" +
                        " · ${Money.cny(state.totalCostUsd)}" +
                        " · ${Money.tokenCount(state.tokensIn + state.tokensOut)} tok" +
                        " · 第 ${it.plot.act} 章 ${it.plot.title}" +
                        if (it.isolatedCount() > 0) " · ${it.isolatedCount()} 人未连线" else ""
                }.orEmpty(),
                layout = state.layout,
                onLayout = { vm.setLayout(it) },
                onBack = onBack,
                onAdd = { vm.beginNewCharacter() },
                onModels = { societyModelOpen = true },
                onSettings = onOpenSettings,
                statusBarPadding = padding.calculateTopPadding() / 2,
            )

            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            if (bundle == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("这个社会的数据读不到了，可能已被删除。")
                }
                return@Column
            }

            // ── 关系图 ──
            val layoutResult: GraphLayoutResult = remember(
                bundle.characters, bundle.relations, state.layout, bundle.society.id,
            ) {
                RelationGraphLayout.compute(
                    society = bundle.society,
                    characters = bundle.characters,
                    relations = bundle.relations,
                    layout = state.layout,
                )
            }

            Box(Modifier.weight(1f)) {
                if (layoutResult.nodes.isEmpty() && layoutResult.isolated.isEmpty()) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(40.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "还没有人物",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "先加一个「你」扮演的主角，剩下的交给旁白按剧情生成。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    // 用户拖拽出的节点位移，转成 dp Offset 传给图
                    val nodeOffsets = remember(state.nodePositions) {
                        state.nodePositions.mapValues { (_, xy) ->
                            androidx.compose.ui.geometry.Offset(xy[0], xy[1])
                        }
                    }
                    RelationGraph(
                        layoutResult = layoutResult,
                        selectedId = selected?.id,
                        positions = nodeOffsets,
                        onNodeDrag = { id, delta -> vm.dragNode(id, delta.x, delta.y) },
                        onDragEnded = { vm.persistNodePositions() },
                        onPlaceNode = { id, center -> vm.placeNode(id, center.x, center.y) },
                        onUnplaceNode = { vm.unplaceNode(it) },
                        onNodeClick = { selected = it },
                        // 底部三个按钮悬浮在画布之上，这里把画布让出它们的高度，
                        // 否则链式布局最下面那个节点的姓名会被压在按钮底下。
                        modifier = Modifier.padding(bottom = 62.dp),
                    )
                }

                // 底部工具条：浮在图上，做半透明
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .padding(bottom = padding.calculateBottomPadding() + 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { narratorOpen = true },
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp),
                    ) {
                        KithIcon(
                            KithIcons.Spark,
                            size = 17.dp,
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(7.dp))
                        Text("旁白")
                    }
                    FilledTonalButton(
                        onClick = onOpenLogs,
                        contentPadding = PaddingValues(horizontal = 13.dp),
                        modifier = Modifier.height(42.dp),
                    ) {
                        KithIcon(
                            KithIcons.Log,
                            size = 17.dp,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(Modifier.width(5.dp))
                        Text("日志")
                    }
                    Box {
                        FilledTonalButton(
                            onClick = { peopleMenuOpen = true },
                            contentPadding = PaddingValues(horizontal = 13.dp),
                            modifier = Modifier.height(42.dp),
                        ) {
                            KithIcon(
                                KithIcons.People,
                                size = 17.dp,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Spacer(Modifier.width(5.dp))
                            Text("人物")
                        }
                        DropdownMenu(
                            expanded = peopleMenuOpen,
                            onDismissRequest = { peopleMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("新建人物") },
                                onClick = {
                                    peopleMenuOpen = false
                                    vm.beginNewCharacter()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("从文件导入人物") },
                                leadingIcon = {
                                    KithIcon(KithIcons.Import, size = 17.dp)
                                },
                                onClick = {
                                    peopleMenuOpen = false
                                    charImportLauncher.launch(arrayOf("application/json", "*/*"))
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // ── 人物详情 ──
    selected?.let { c ->
        CharacterDetailSheet(
            character = c,
            bundle = bundle,
            onDismiss = { selected = null },
            onChat = { onOpenChat(c.id) },
            onEdit = { vm.beginEditCharacter(c) },
            onPickModel = {
                vm.requestPick(SocietyPickTarget.CHARACTER, c.id)
                onNavigateToPicker()
            },
            onDelete = {
                vm.deleteCharacter(c)
                selected = null
            },
        )
    }

    // ── 旁白面板 ──
    if (narratorOpen && bundle != null) {
        NarratorSheet(
            bundle = bundle,
            running = state.narratorRunning,
            lastNarrative = state.narratorText,
            notice = state.notice,
            onRun = { hint, needNpc -> vm.runNarrator(hint, needNpc) },
            onDismiss = { narratorOpen = false },
        )
    }

    // 人物编辑：草稿非空即视为打开。跳去选模型再回来，草稿仍在。
    if (characterDraft != null) {
        CharacterEditorDialog(
            draft = characterDraft,
            isExisting = vm.isEditingExistingDraft(),
            onChange = { updated -> vm.updateDraft { updated } },
            onPickModel = {
                vm.requestPick(SocietyPickTarget.CHARACTER, characterDraft.id)
                onNavigateToPicker()
            },
            onSave = { vm.commitDraft() },
            onDismiss = { vm.cancelDraft() },
        )
    }

    if (societyModelOpen && bundle != null) {
        SocietyModelSheet(
            bundle = bundle,
            onSlot = { slot ->
                vm.requestPick(slot)
                societyModelOpen = false
                onNavigateToPicker()
            },
            onCoverUpdated = { vm.reload() },
            onDismiss = { societyModelOpen = false },
        )
    }
}

/**
 * 社会模型面板。
 *
 * 只负责「选哪个槽位」，真正的模型挑选已经改到独立的配置页
 * （[com.kith.app.ui.settings.ModelPickerScreen]）—— 三个槽位的选择本身很小，
 * 但候选模型最多 371 条，需要整屏的搜索与筛选。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SocietyModelSheet(
    bundle: com.kith.app.data.store.SocietyBundle,
    onSlot: (SocietyPickTarget) -> Unit,
    onCoverUpdated: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coverScope = rememberCoroutineScope()
    val coverContext = androidx.compose.ui.platform.LocalContext.current
    val coverSocietyId = bundle.society.id

    // 从相册选一张图当封面：拷进该社会的 media/，coverImage 记文件名
    val pickCover = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coverScope.launch {
            val bytes = runCatching {
                coverContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull() ?: return@launch
            val ext = coverContext.contentResolver.getType(uri)
                ?.substringAfterLast('/')?.ifEmpty { "jpg" } ?: "jpg"
            val f = kithGraph.store.saveMedia(coverSocietyId, bytes, if (ext == "jpeg") "jpg" else ext)
            kithGraph.store.saveSociety(
                bundle.society.copy(
                    coverImage = f.name,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            onCoverUpdated()
        }
    }

    fun resetCover() {
        coverScope.launch {
            kithGraph.store.saveSociety(
                bundle.society.copy(coverImage = "", updatedAt = System.currentTimeMillis()),
            )
            onCoverUpdated()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text("「${bundle.society.name}」的模型", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            SocietalSlotRow(
                title = "旁白模型",
                desc = "推进剧情、生成 NPC、判断重要性",
                label = bundle.society.narratorModel?.display(),
                modelId = bundle.society.narratorModel?.modelId,
                onClick = { onSlot(SocietyPickTarget.NARRATOR) },
                warn = bundle.society.narratorModel == null,
            )
            Spacer(Modifier.height(8.dp))
            SocietalSlotRow(
                title = "新人物默认模型",
                desc = "旁白生成 NPC 时的兜底；单个角色可再单独覆盖",
                label = bundle.society.defaultCharacterModel?.display(),
                modelId = bundle.society.defaultCharacterModel?.modelId,
                onClick = { onSlot(SocietyPickTarget.DEFAULT_CHARACTER) },
            )
            Spacer(Modifier.height(8.dp))
            SocietalSlotRow(
                title = "文生图模型",
                desc = "生成人物头像与聊天里的配图",
                label = bundle.society.imageModel?.display(),
                modelId = bundle.society.imageModel?.modelId,
                onClick = { onSlot(SocietyPickTarget.IMAGE) },
            )

            // ── 封面 ──
            Spacer(Modifier.height(18.dp))
            Text("封面", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val coverName = bundle.society.coverImage
                val coverFile = coverName.takeIf { it.isNotEmpty() }
                    ?.let { java.io.File(kithGraph.store.mediaDir(bundle.society.id), it) }
                    ?.takeIf { it.exists() }
                val hue = (kotlin.math.abs(bundle.society.coverSeed) % 360).toFloat()
                Box(
                    Modifier
                        .size(width = 96.dp, height = 56.dp)
                        .clip(MaterialTheme.shapes.small),
                ) {
                    if (coverFile != null) {
                        coil.compose.AsyncImage(
                            model = coverFile,
                            contentDescription = "封面",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                    } else {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            Color.hsl(hue, 0.32f, 0.72f),
                                            Color.hsl((hue + 42f) % 360f, 0.38f, 0.55f),
                                        ),
                                    ),
                                ),
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    TextButton(onClick = { pickCover.launch("image/*") }) {
                        Text(if (coverFile != null) "更换封面" else "从相册选择")
                    }
                    if (coverName.isNotEmpty()) {
                        TextButton(onClick = { resetCover() }) {
                            Text("恢复默认")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SocietalSlotRow(
    title: String,
    desc: String,
    label: String?,
    modelId: String?,
    onClick: () -> Unit,
    warn: Boolean = false,
) {
    KithCard(onClick = onClick) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (label != null) {
                com.kith.app.ui.common.VendorMark(
                    com.kith.app.data.catalog.VendorRegistry.of(
                        com.kith.app.kithGraph.settings.current
                            .savedModels.firstOrNull { it.modelId == modelId }?.vendor
                            ?: bundleVendorFallback(modelId),
                    ),
                    size = 30.dp,
                )
            } else {
                KithIcon(KithIcons.Chip, size = 22.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    if (warn) {
                        Spacer(Modifier.width(6.dp))
                        Pill("必需", MaterialTheme.colorScheme.error)
                    }
                }
                Text(
                    label ?: desc,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                if (label == null) "选择" else "更换",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun bundleVendorFallback(modelId: String?): String =
    com.kith.app.kithGraph.catalog.find(modelId.orEmpty())?.vendorKey ?: "custom"


// ── 社会页头部（紧凑版）──────────────────────────────────────────────────────

/**
 * 社会页专用的紧凑头部。
 *
 * 为什么不复用通用的 [com.kith.app.ui.common.GlassTopBar]：
 * 关系图是这个页面唯一的、也是占满剩余空间的内容，头部每多占 10dp，
 * 图就少 10dp。通用顶栏是「标题 / 副标题 / 数据块」三行结构（约 122dp），
 * 再叠加一行独立的布局切换（约 48dp），一共吃掉约 170dp。
 *
 * 这里压成两行：
 *   第一行：返回 + 社会名 + 走向标签 …… ＋人物 / 模型 / 设置
 *   第二行：一行紧凑数据 …… 树状｜链式 分段开关
 * 总共约 104dp，比原来省下约 66dp 全部让给关系图。
 */
@Composable
private fun SocietyHeader(
    title: String,
    orientation: String,
    meta: String,
    layout: GraphLayout,
    onLayout: (GraphLayout) -> Unit,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onModels: () -> Unit,
    onSettings: () -> Unit,
    statusBarPadding: Dp,
) {
    val colors = LocalKithColors.current
    Box(Modifier.fillMaxWidth()) {
        // 与通用顶栏一致的毛玻璃底，滚动时下方关系图会透出来糊开
        Box(
            Modifier
                .matchParentSize()
                .blur(18.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = statusBarPadding + 2.dp, bottom = 6.dp)
                .padding(horizontal = 4.dp),
        ) {
            // 第一行
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (orientation.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Pill(orientation, MaterialTheme.colorScheme.tertiary)
                }
                Spacer(Modifier.weight(1f))
                HeaderIconButton(KithIcons.People, "添加人物", onAdd)
                HeaderIconButton(KithIcons.Chip, "这个社会的模型", onModels)
                HeaderIconButton(KithIcons.Globe, "全局设置", onSettings)
            }
            Spacer(Modifier.height(2.dp))
            // 第二行
            Row(
                Modifier.padding(start = 10.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                LayoutSegmented(layout = layout, onLayout = onLayout)
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(0.6.dp)
                .background(colors.glassBorder),
        )
    }
}

@Composable
private fun HeaderIconButton(res: Int, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
        KithIcon(
            res,
            contentDescription = label,
            size = 19.dp,
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 树状 / 链式 的分段开关。
 * 用两个图标代替原来的两个 FilterChip（带文字），宽度从约 200dp 压到 84dp，
 * 正好塞进头部第二行，省掉了整行独立的切换区。
 */
@Composable
private fun LayoutSegmented(
    layout: GraphLayout,
    onLayout: (GraphLayout) -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SegmentedItem(
            res = KithIcons.Tree,
            label = "树状图",
            selected = layout == GraphLayout.TREE,
        ) { onLayout(GraphLayout.TREE) }
        SegmentedItem(
            res = KithIcons.Chain,
            label = "链式图",
            selected = layout == GraphLayout.CHAIN,
        ) { onLayout(GraphLayout.CHAIN) }
    }
}

@Composable
private fun SegmentedItem(
    res: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(width = 38.dp, height = 26.dp)
            .clip(RoundedCornerShape(999.dp))
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.primary)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        KithIcon(
            res,
            contentDescription = label,
            size = 15.dp,
            tint = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

// ── 人物详情卡 ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CharacterDetailSheet(
    character: Character,
    bundle: com.kith.app.data.store.SocietyBundle?,
    onDismiss: () -> Unit,
    onChat: () -> Unit,
    onEdit: () -> Unit,
    onPickModel: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmDelete by remember { mutableStateOf(false) }
    val relations = bundle?.relationsOf(character.id).orEmpty()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CharacterAvatar(
                    name = character.name,
                    size = 64.dp,
                    avatarUrl = character.avatarUrl,
                    importance = character.importance,
                    isUser = character.isUser,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        character.displayName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(character.importance.label, character.importance.color())
                        if (character.gender.label != "未设定") Pill(character.gender.label)
                        if (character.age.isNotEmpty()) Pill(character.age)
                        if (character.isUser) Pill("你", MaterialTheme.colorScheme.secondary)
                        if (character.isGenerated) Pill("旁白生成", MaterialTheme.colorScheme.tertiary)
                    }
                }
            }

            if (character.oneLiner.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(character.oneLiner, style = MaterialTheme.typography.bodyLarge)
            }

            if (character.tags.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("与你的关系", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                character.tags.forEach { t ->
                    KithCard(Modifier.padding(bottom = 6.dp)) {
                        Column(Modifier.padding(10.dp)) {
                            Text(t.label, style = MaterialTheme.typography.titleSmall)
                            if (t.hint.isNotEmpty()) {
                                Text(
                                    t.hint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            DetailBlock("性格", character.personality)
            DetailBlock("背景", character.background)
            DetailBlock("外貌", character.appearance)
            DetailBlock("说话风格", character.speechStyle)

            Spacer(Modifier.height(14.dp))
            Text("关系网（${relations.size} 条）", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            if (relations.isEmpty()) {
                Text(
                    "还没有与其他人的连线。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                relations.forEach { r ->
                    val otherId = if (r.fromId == character.id) r.toId else r.fromId
                    val other = bundle?.character(otherId)
                    Row(
                        Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (r.fromId == character.id) "→" else "←",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(other?.name ?: "已删除", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(8.dp))
                        Pill(r.label, MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "亲密 ${r.intensity}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text("使用的模型", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            KithCard(onClick = onPickModel) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    KithIcon(KithIcons.Chip, size = 20.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            character.model?.display() ?: "跟随社会默认",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            character.model?.modelId ?: "未单独指定",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "更换",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (character.importance.chatEnabled && !character.isUser) {
                    Button(onClick = onChat, modifier = Modifier.weight(1f)) {
                        Text("给 TA 发消息")
                    }
                } else {
                    Text(
                        if (character.isUser) "这是你本人" else "此重要度的人物只展示资料",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedButton(onClick = onEdit) { Text("编辑") }
                OutlinedButton(onClick = { confirmDelete = true }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除「${character.name}」？") },
            text = { Text("TA 的资料、所有聊天记录，以及与其他人的关系连线都会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun DetailBlock(label: String, value: String) {
    if (value.isBlank()) return
    Spacer(Modifier.height(12.dp))
    Text(label, style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(3.dp))
    Text(value, style = MaterialTheme.typography.bodyMedium)
}

// ── 旁白面板 ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NarratorSheet(
    bundle: com.kith.app.data.store.SocietyBundle,
    running: Boolean,
    lastNarrative: String,
    notice: String,
    onRun: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var hint by remember { mutableStateOf("") }
    var needNpc by remember { mutableStateOf(false) }
    val plot = bundle.plot

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KithIcon(KithIcons.Narrator, size = 24.dp, tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("旁白", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "推进剧情、按需引入新人物，并为人与事分配模型档位",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            KithCard {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "第 ${plot.act} 章 · ${plot.title}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (plot.summary.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(plot.summary, style = MaterialTheme.typography.bodySmall)
                    }
                    if (plot.mood.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Pill(plot.mood, MaterialTheme.colorScheme.tertiary)
                    }
                }
            }

            if (plot.hooks.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("尚未回收的伏笔", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                plot.hooks.takeLast(6).forEach {
                    Text("· $it", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (plot.beats.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("最近发生", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                plot.beats.takeLast(6).reversed().forEach { b ->
                    Column(Modifier.padding(vertical = 3.dp)) {
                        Text(b.title, style = MaterialTheme.typography.bodySmall)
                        if (b.detail.isNotEmpty()) {
                            Text(
                                b.detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (lastNarrative.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("最新旁白", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                KithCard {
                    Text(
                        lastNarrative,
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (notice.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                value = hint,
                onValueChange = { hint = it },
                label = { Text("给旁白的指示（可留空）") },
                placeholder = { Text("如：让主角在便利店遇到一个旧识") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("顺带引入新人物", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "旁白会创造一个与现有关系网接得上的人，并按重要性自动分配模型",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = needNpc, onCheckedChange = { needNpc = it })
            }

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { onRun(hint, needNpc) },
                enabled = !running,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) {
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("旁白正在构思…")
                } else {
                    KithIcon(KithIcons.Spark, size = 18.dp, tint = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("让旁白推进一段")
                }
            }
        }
    }
}

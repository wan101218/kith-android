package com.kith.app.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kith.app.data.store.SocietySummary
import com.kith.app.domain.PlotOrientation
import com.kith.app.kithGraph
import com.kith.app.ui.common.EmptyState
import com.kith.app.ui.common.GlassTopBar
import com.kith.app.ui.common.KithCard
import com.kith.app.ui.common.KithIcons
import com.kith.app.ui.common.KithIcon
import com.kith.app.ui.common.Pill
import com.kith.app.ui.common.StatItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 社会列表页的状态。 */
data class HomeUiState(
    val loading: Boolean = true,
    val societies: List<SocietySummary> = emptyList(),
    val message: String = "",
)

class HomeViewModel : ViewModel() {

    private val store = kithGraph.store
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val list = store.list()
            _state.value = HomeUiState(loading = false, societies = list)
        }
    }

    suspend fun buildArchive(societyId: String): String? = store.exportArchive(societyId)

    fun importArchive(raw: String, onDone: suspend (String) -> Unit) {
        viewModelScope.launch {
            store.importArchive(raw)
                .onSuccess {
                    refresh()
                    onDone("已导入社会「${it.name}」")
                }
                .onFailure { onDone("导入失败：${it.message}") }
        }
    }

    fun delete(societyId: String, onDone: suspend (String) -> Unit) {
        viewModelScope.launch {
            store.delete(societyId)
            refresh()
            onDone("已删除")
        }
    }
}

@Composable
fun HomeScreen(
    onCreate: () -> Unit,
    onOpen: (String) -> Unit,
    vm: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel() } }),
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<SocietySummary?>(null) }
    var pendingExport by remember { mutableStateOf<SocietySummary?>(null) }
    val scope = rememberCoroutineScope()

    // 从社会页（可能刚换过封面/改过设定）回到首页时重读索引，
    // 否则列表卡片显示的还是旧的封面与信息
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    // 导出：先问文件名，再用系统「创建文档」写入
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val target = pendingExport
        pendingExport = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        scope.launch {
            val json = vm.buildArchive(target.id)
            if (json == null) {
                snackbar.showSnackbar("导出失败：读不到这个社会")
                return@launch
            }
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            }.isSuccess
            snackbar.showSnackbar(if (ok) "已导出「${target.name}」" else "写入文件失败")
        }
    }

    // 导入：选一个 json 读进来
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val raw = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (raw.isNullOrBlank()) {
                snackbar.showSnackbar("读取文件失败")
            } else {
                vm.importArchive(raw) { snackbar.showSnackbar(it) }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreate,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("新建社会") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize()) {
            GlassTopBar(
                title = "Kith",
                subtitle = "造一个社会，看它自己活起来",
                statusBarPadding = padding.calculateTopPadding() / 2,
                stats = listOf(
                    StatItem("个社会", state.societies.size.toString()),
                    StatItem("位人物", state.societies.sumOf { it.characterCount }.toString()),
                    StatItem("条关系", state.societies.sumOf { it.relationCount }.toString()),
                ),
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) {
                        KithIcon(KithIcons.Import, contentDescription = "导入社会", size = 21.dp)
                    }
                },
            )

            if (!state.loading && state.societies.isEmpty()) {
                Spacer(Modifier.height(40.dp))
                EmptyState(
                    iconRes = KithIcons.People,
                    title = "还没有属于你的社会",
                    subtitle = "每个社会都是一个独立的世界，有自己的世界观、人物和故事线，" +
                        "彼此完全隔离。先创建一个开始吧。",
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp,
                        bottom = padding.calculateBottomPadding() + 96.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.societies, key = { it.id }) { s ->
                        SocietyCard(
                            summary = s,
                            onOpen = { onOpen(s.id) },
                            onMenu = { menuFor = s.id },
                            menuOpen = menuFor == s.id,
                            onDismissMenu = { menuFor = null },
                            onExport = {
                                menuFor = null
                                pendingExport = s
                                exportLauncher.launch("${s.name}.kith.json")
                            },
                            onDelete = {
                                menuFor = null
                                confirmDelete = s
                            },
                        )
                    }
                }
            }
        }
    }

    confirmDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除这个社会？") },
            text = {
                Text(
                    "「${target.name}」的全部内容 —— 社会设定、${target.characterCount} 位人物、" +
                        "${target.relationCount} 条关系、所有聊天记录与日志 —— 都会被删除，" +
                        "无法恢复。\n\n建议先导出备份。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = target.id
                    confirmDelete = null
                    vm.delete(id) { snackbar.showSnackbar(it) }
                }) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SocietyCard(
    summary: SocietySummary,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    menuOpen: Boolean,
    onDismissMenu: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val orientation = PlotOrientation.byCode(summary.orientationCode)
    val hue = (kotlin.math.abs(summary.coverSeed) % 360).toFloat()
    // 自定义封面优先；文件丢了（被清理等）就回退到程序化渐变
    val coverFile = summary.coverImage
        .takeIf { it.isNotEmpty() }
        ?.let { java.io.File(kithGraph.store.mediaDir(summary.id), it) }
        ?.takeIf { it.exists() }

    KithCard(onClick = onOpen) {
        Column {
            // 封面：默认由 coverSeed 派生稳定渐变，设置过封面图则显示图
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(88.dp),
            ) {
                if (coverFile != null) {
                    coil.compose.AsyncImage(
                        model = coverFile,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().height(88.dp),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(88.dp)
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
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                ) {
                    IconButton(
                        onClick = onMenu,
                        modifier = Modifier
                            .size(32.dp)
                            // 封面可能是浅色图或浅粉彩渐变，白图标直接压上去会看不见；
                            // 垫一层半透明墨色圆底，两种主题、任意封面上都可读
                            .background(Color.Black.copy(alpha = 0.28f), CircleShape),
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "更多",
                            tint = Color.White,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
                        DropdownMenuItem(
                            text = { Text("导出为 JSON") },
                            onClick = onExport,
                            leadingIcon = {
                                KithIcon(KithIcons.Export, size = 18.dp)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("删除社会", color = MaterialTheme.colorScheme.error) },
                            onClick = onDelete,
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                        )
                    }
                }
            }

            Column(Modifier.padding(12.dp)) {
                Text(
                    text = summary.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Pill(orientation.label, MaterialTheme.colorScheme.tertiary)
                    Text(
                        text = "${summary.characterCount} 人 · ${summary.relationCount} 关系",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

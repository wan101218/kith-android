package com.kith.app.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.kith.app.ui.common.GlassTopBar
import com.kith.app.ui.common.KithCard
import com.kith.app.ui.common.KithIcon
import com.kith.app.ui.common.KithIcons
import com.kith.app.ui.common.SectionHeader
import kotlinx.coroutines.launch
import java.io.File

/**
 * 资源库管理页 —— 「需要添加的库」的统一入口。
 *
 * - **AI 表情包库**：按社会隔离，用户从相册选图添加、给情绪词，
 *   AI 的提示词里会列出这些表情，模型学会发它们。
 * - **审核模型文件**：Qwen3Guard 0.6B 的 .task/.bin 在这里导入。
 *
 * 其他界面（聊天表情面板、设置页审核卡片）只放跳转引导，不重复做管理功能。
 */
@Composable
fun LibraryScreen(
    onBack: () -> Unit,
    vm: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 待添加的表情
    var pendingBytes by remember { mutableStateOf(ByteArray(0)) }
    var pendingExt by remember { mutableStateOf("png") }
    var pendingName by remember { mutableStateOf("") }
    var pendingEmotions by remember { mutableStateOf("") }

    LaunchedEffect(state.toast) {
        if (state.toast.isNotEmpty()) {
            snackbar.showSnackbar(state.toast)
            vm.consumeToast()
        }
    }

    val pickStickerImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull() ?: return@launch
            pendingBytes = bytes
            pendingExt = context.contentResolver.getType(uri)
                ?.substringAfterLast('/') ?: "jpg"
            if (pendingName.isBlank()) pendingName = "表情${state.stickers.size + 1}"
        }
    }

    val pickGuardModel = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        vm.importGuardModel {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }
    }

    LaunchedEffect(Unit) { vm.refreshGuardModel() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize()) {
            GlassTopBar(
                title = "资源库",
                subtitle = "表情包与模型文件，统一在这里添加",
                onBack = onBack,
                statusBarPadding = padding.calculateTopPadding() / 2,
            )

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    bottom = padding.calculateBottomPadding() + 32.dp,
                ),
            ) {
                // ── AI 表情包库 ─────────────────────────────────────────
                item { SectionHeader("AI 表情包库（按社会隔离）") }

                item {
                    if (state.societies.isEmpty()) {
                        Text(
                            "还没有社会。先去创建一个。",
                            modifier = Modifier.padding(horizontal = 20.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            state.societies.forEach { (id, name) ->
                                FilterChip(
                                    selected = id == state.selectedId,
                                    onClick = { vm.select(id) },
                                    label = { Text(name) },
                                )
                            }
                        }
                    }
                }

                item {
                    KithCard(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (state.stickers.isEmpty()) {
                                Text(
                                    "还没有专属表情",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                state.stickers.chunked(3).forEach { rowItems ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        rowItems.forEach { s ->
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                modifier = Modifier.weight(1f),
                                            ) {
                                                Box {
                                                    AsyncImage(
                                                        model = if (s.imageUrl.startsWith("/")) {
                                                            File(s.imageUrl)
                                                        } else {
                                                            s.imageUrl
                                                        },
                                                        contentDescription = s.name,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(72.dp)
                                                            .clip(MaterialTheme.shapes.small)
                                                            .background(
                                                                MaterialTheme.colorScheme.surfaceVariant,
                                                            ),
                                                        contentScale = ContentScale.Fit,
                                                    )
                                                    // 删除角标
                                                    Box(
                                                        Modifier
                                                            .align(Alignment.TopEnd)
                                                            .padding(3.dp)
                                                            .size(20.dp)
                                                            .background(
                                                                MaterialTheme.colorScheme.errorContainer,
                                                                CircleShape,
                                                            )
                                                            .clickable { vm.removeSticker(s.id) },
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        Text(
                                                            "×",
                                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                                            style = MaterialTheme.typography.labelMedium,
                                                            fontWeight = FontWeight.Bold,
                                                        )
                                                    }
                                                }
                                                Spacer(Modifier.height(3.dp))
                                                Text(
                                                    s.name,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    maxLines = 1,
                                                )
                                            }
                                        }
                                        repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }

                            // 添加表单
                            androidx.compose.material3.HorizontalDivider()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clickable { pickStickerImage.launch("image/*") },
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        if (pendingBytes.isEmpty()) {
                                            Text("+", style = MaterialTheme.typography.titleLarge)
                                        } else {
                                            AsyncImage(
                                                model = pendingBytes,
                                                contentDescription = null,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop,
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    if (pendingBytes.isEmpty()) "点这里选一张图（相册/文件）"
                                    else "已选好图，起名并填情绪词",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            OutlinedTextField(
                                value = pendingName,
                                onValueChange = { pendingName = it },
                                label = { Text("表情名字") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = pendingEmotions,
                                onValueChange = { pendingEmotions = it },
                                label = { Text("适用情绪（顿号分隔，如：笑哭、无语）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                onClick = {
                                    vm.addSticker(pendingBytes, pendingExt, pendingName, pendingEmotions)
                                    pendingBytes = ByteArray(0)
                                    pendingName = ""
                                    pendingEmotions = ""
                                },
                                enabled = pendingBytes.isNotEmpty() && pendingName.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("添加到库")
                            }
                        }
                    }
                }

                // ── 审核模型文件 ─────────────────────────────────────────
                item { SectionHeader("本地审核模型") }

                item {
                    val dl by vm.download.collectAsState()
                    KithCard(Modifier.padding(horizontal = 16.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                KithIcon(KithIcons.Flask, size = 20.dp)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Qwen3Guard 0.6B", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        state.guardModelInfo,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            // ── 在线下载 ──
                            Text(
                                "在线下载",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                com.kith.app.ai.guard.ContentGuard.DOWNLOAD_SOURCES
                                    .forEachIndexed { idx, (label, _) ->
                                        FilterChip(
                                            selected = state.downloadSourceIdx == idx,
                                            onClick = { vm.selectDownloadSource(idx) },
                                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                        )
                                    }
                            }

                            when (val d = dl) {
                                is com.kith.app.ai.guard.GuardDownloadState.Downloading -> {
                                    val pct = if (d.totalMB > 0) {
                                        (d.downloadedMB * 100 / d.totalMB).toInt()
                                    } else 0
                                    Column {
                                        androidx.compose.material3.LinearProgressIndicator(
                                            progress = { pct / 100f },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                if (d.totalMB > 0) {
                                                    "下载中 $pct%（${d.downloadedMB}/${d.totalMB}MB）"
                                                } else {
                                                    "下载中 ${d.downloadedMB}MB"
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.weight(1f),
                                            )
                                            TextButton(onClick = { vm.cancelDownload() }) {
                                                Text("取消")
                                            }
                                        }
                                    }
                                }
                                else -> {
                                    Button(
                                        onClick = { vm.startDownload() },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text("开始下载")
                                    }
                                }
                            }

                            androidx.compose.material3.HorizontalDivider()

                            // ── 本地导入（备用）──
                            Text(
                                "本地导入（.task / .bin / .gguf）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { pickGuardModel.launch(arrayOf("*/*")) }) {
                                KithIcon(KithIcons.Import, size = 15.dp)
                                Spacer(Modifier.width(4.dp))
                                Text("选择文件导入")
                            }
                            Text(
                                "完成后在「设置 → 内容审核」打开开关 · 模型不出本机",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

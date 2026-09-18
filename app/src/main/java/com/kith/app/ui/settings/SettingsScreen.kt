package com.kith.app.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Delete
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kith.app.ai.engine.Money
import com.kith.app.data.catalog.VendorRegistry
import com.kith.app.data.settings.GraphLayout
import com.kith.app.data.settings.SettingsSnapshot
import com.kith.app.data.settings.ThemeMode
import com.kith.app.domain.AiEndpoint
import com.kith.app.domain.EndpointKind
import com.kith.app.domain.ModelRef
import com.kith.app.kithGraph
import com.kith.app.ui.common.GlassTopBar
import com.kith.app.ui.common.KithCard
import com.kith.app.ui.common.KithIcons
import com.kith.app.ui.common.KithIcon
import com.kith.app.ui.common.Pill
import com.kith.app.ui.common.SectionHeader
import com.kith.app.ui.common.VendorMark
import kotlinx.coroutines.launch

/**
 * 设置页。
 *
 * 这里放的是**跨社会共享**的配置：接入点与密钥、已保存的模型、视觉桥接、
 * 模型目录、玩法开关、界面偏好。每个社会自己的内容不在这里，而在那个社会的
 * 独立文件夹里。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNewEndpoint: (EndpointKind) -> Unit,
    onEditEndpoint: (AiEndpoint) -> Unit,
    /** 跳到资源库管理页（模型文件、表情包库统一在那里添加） */
    onNavigateToLibrary: () -> Unit = {},
) {
    val graph = kithGraph
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var vendorFilterOpen by remember { mutableStateOf(false) }
    // 汇率单独存文本，避免用户正在输入 "7." 时被解析成非法值后回弹
    var rateText by remember { mutableStateOf(settings.usdToCnyRate.toString()) }
    var visionModel by remember { mutableStateOf(settings.visionModel) }
    var visionKey by remember { mutableStateOf(settings.visionApiKey) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize()) {
            GlassTopBar(
                title = "设置",
                subtitle = "接入点与密钥只存在本机",
                onBack = onBack,
                statusBarPadding = padding.calculateTopPadding() / 2,
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = padding.calculateBottomPadding() + 32.dp),
            ) {
                // ── 内容审核（本地 Qwen3Guard 0.6B）────────────────────────
                // 放在最显眼的位置（设置页第一块）：它直接影响旁白输出的可信度，
                // 且涉及内存占用与性能门槛，用户必须随时知道它的状态。
                val cap = remember {
                    com.kith.app.ai.guard.DeviceCapabilityCheck.check(context)
                }
                var modelInfo by remember {
                    mutableStateOf(graph.guard.modelFile()?.let { f ->
                        "已导入 ${String.format(java.util.Locale.US, "%.0f", f.length() / 1048576.0)}MB"
                    } ?: "未导入模型文件")
                }
                var guardConfirmOpen by remember { mutableStateOf(false) }

                SectionHeader("内容审核（本地）")
                KithCard(Modifier.padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Qwen3Guard 0.6B 审核", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "旁白输出在本机自动检查",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = settings.guardEnabled,
                                // 性能不达标的设备直接不给开 —— 内存和算力都跑不动 0.6B 模型
                                enabled = cap.meetsRequirement,
                                onCheckedChange = { want ->
                                    if (want) {
                                        guardConfirmOpen = true
                                    } else {
                                        graph.settings.setGuardEnabled(false)
                                        scope.launch { graph.guard.release() }
                                    }
                                },
                            )
                        }

                        Text(
                            buildString {
                                append("本机：${cap.summary()}")
                                if (!cap.meetsRequirement) {
                                    append("（未达标，审核不可用）")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (cap.meetsRequirement) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        Text(
                            "模型：$modelInfo",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        // 导入动作统一收进资源库管理页，这里只放引导
                        TextButton(onClick = onNavigateToLibrary) {
                            Text("资源库 →")
                        }
                    }
                }

                if (guardConfirmOpen) {
                    AlertDialog(
                        onDismissRequest = { guardConfirmOpen = false },
                        title = { Text("开启本地审核？") },
                        text = {
                            Text(
                                "Qwen3Guard 0.6B 将常驻本机内存：模型文件约 0.4–0.9GB，" +
                                    "加载后运行时总占用约 1GB。\n\n" +
                                    "内存紧张时系统可能杀掉 Kith 或其它后台应用。" +
                                    "关闭审核后内存会立即释放。",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                guardConfirmOpen = false
                                graph.settings.setGuardEnabled(true)
                            }) { Text("仍要开启") }
                        },
                        dismissButton = {
                            TextButton(onClick = { guardConfirmOpen = false }) { Text("取消") }
                        },
                    )
                }

                // ── 接入点 ──────────────────────────────────────────────────
                SectionHeader("模型接入点", trailing = {
                    TextButton(onClick = { onNewEndpoint(EndpointKind.LLM) }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("添加")
                    }
                })
                Column(
                    Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (settings.endpoints.isEmpty()) {
                        HintCard("还没有接入点。至少配一个对话模型，旁白和人物才能工作。")
                    } else {
                        settings.endpoints.forEach { ep ->
                            EndpointRow(
                                endpoint = ep,
                                onEdit = { onEditEndpoint(ep) },
                                onDelete = { graph.settings.removeEndpoint(ep.id) },
                            )
                        }
                    }
                    TextButton(onClick = { onNewEndpoint(EndpointKind.IMAGE) }) {
                        KithIcon(KithIcons.ImageGen, size = 17.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("添加文生图接入点")
                    }
                }

                // ── 已保存的模型 ────────────────────────────────────────────
                if (settings.savedModels.isNotEmpty()) {
                    SectionHeader("已保存的模型配置")
                    Column(
                        Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        settings.savedModels.forEach { ref ->
                            SavedModelRow(ref) { graph.settings.removeSavedModel(ref) }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "这些配置可以在任意社会、任意人物上直接复用。",
                        modifier = Modifier.padding(horizontal = 20.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // ── 模型目录：已从界面移除 ─────────────────────────────────
                // 目录随 APK 内置（assets/model_catalog.json），按「接入点实测/
                // 官方文档」人工维护；旧的 URL 刷新入口依赖本地自动化任务，
                // 已停用，界面不再展示。

                // ── 计费 ────────────────────────────────────────────────────
                SectionHeader("计费")
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        "模型的实际计价单位是美元，界面统一换算成人民币显示。" +
                            "各人拿到的报价不一样（走中转站的、有企业折扣的、按银行现汇价的），" +
                            "所以汇率可以自己改，改完所有界面立刻生效。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    val parsed = rateText.trim().toDoubleOrNull()
                    val valid = parsed != null && parsed.isFinite() && parsed in 0.5..50.0
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = rateText,
                            onValueChange = { t ->
                                rateText = t
                                // 只在输入合法时才落盘，避免输到一半把汇率写成非法值
                                t.trim().toDoubleOrNull()
                                    ?.takeIf { it.isFinite() && it in 0.5..50.0 }
                                    ?.let { graph.settings.setUsdToCnyRate(it) }
                            },
                            label = { Text("1 美元 = ? 人民币") },
                            singleLine = true,
                            isError = !valid,
                            supportingText = {
                                Text(
                                    if (valid) {
                                        "目录里 $1 的单价会显示为 ${Money.cny(1.0)}"
                                    } else {
                                        "请输入 0.5 – 50 之间的数字"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            rateText = Money.DEFAULT_USD_TO_CNY.toString()
                            graph.settings.setUsdToCnyRate(Money.DEFAULT_USD_TO_CNY)
                        }) { Text("恢复默认") }
                    }
                }

                // ── 视觉桥接 ────────────────────────────────────────────────
                SectionHeader("视觉桥接")
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        "有些模型看不了图。开启后，你发的图片会先由视觉模型转述成文字，" +
                            "再交给角色 —— 无论选哪个模型，发图都有人接得住。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    KithCard {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("启用视觉桥接", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (graph.settings.visionKeyMissing()) {
                                        "还没填视觉模型的 Key，桥接不会生效"
                                    } else {
                                        "使用你自己的 Key"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = settings.visionEnabled,
                                onCheckedChange = { graph.settings.updateVision(enabled = it) },
                            )
                        }
                    }
                    if (settings.visionEnabled) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = visionModel,
                            onValueChange = {
                                visionModel = it
                                graph.settings.updateVision(model = it)
                            },
                            label = { Text("视觉模型") },
                            supportingText = { Text("常用：glm-4v-flash（免费）、glm-4.5v、glm-4v-plus") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = visionKey,
                            onValueChange = {
                                visionKey = it
                                graph.settings.updateVision(apiKey = it)
                            },
                            label = { Text("API Key") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = {
                            graph.settings.resetVision()
                            visionModel = SettingsSnapshot.DEFAULT_VISION_MODEL
                            visionKey = SettingsSnapshot.DEFAULT_VISION_KEY
                            scope.launch { snackbar.showSnackbar("已恢复默认视觉配置") }
                        }) { Text("恢复默认") }
                    }
                }

                // ── 旁白行为 ────────────────────────────────────────────────
                SectionHeader("旁白行为")
                Column(
                    Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SwitchRow(
                        title = "自动为新人物分配模型",
                        desc = "按重要性分档：核心用旗舰、配角用经济，控制成本",
                        checked = settings.narratorAutoAssignModel,
                    ) { graph.settings.updateNarrator(it, settings.narratorAutoLinkRelations) }

                    SwitchRow(
                        title = "自动把新人接进关系网",
                        desc = "关闭后新人物会先以孤立状态加入，由你手动连线",
                        checked = settings.narratorAutoLinkRelations,
                    ) { graph.settings.updateNarrator(settings.narratorAutoAssignModel, it) }

                    SwitchRow(
                        title = "进入社会时自动检查剧情",
                        desc = "每次打开社会都让旁白看一眼有没有该推进的节点",
                        checked = settings.autoNarratorOnEnter,
                    ) { graph.settings.setAutoNarratorOnEnter(it) }
                }

                // ── 玩法 ────────────────────────────────────────────────────
                SectionHeader("玩法")
                Column(
                    Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SwitchRow(
                        title = "上帝视角",
                        desc = "可以看到 NPC 之间说了什么，并让两个 NPC 私下对话",
                        checked = settings.godViewEnabled,
                    ) { graph.settings.setGodView(it) }
                }

                // ── 界面 ────────────────────────────────────────────────────
                SectionHeader("界面")
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("主题", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { m ->
                            FilterChip(
                                selected = settings.themeMode == m,
                                onClick = { graph.settings.setThemeMode(m) },
                                label = { Text(m.label) },
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("关系图默认布局", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GraphLayout.entries.forEach { l ->
                            FilterChip(
                                selected = settings.graphLayout == l,
                                onClick = { graph.settings.setGraphLayout(l) },
                                label = { Text(l.label) },
                            )
                        }
                    }
                }

                // ── 关于 ────────────────────────────────────────────────────
                SectionHeader("关于")
                Column(Modifier.padding(horizontal = 16.dp)) {
                    KithCard {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Kith",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.width(8.dp))
                                Pill("v" + appVersionName(context))
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "kith and kin —— 亲友与故交。\n" +
                                    "每个社会都是一个独立文件夹，彼此完全隔离；" +
                                    "导出即为一份可分享的 JSON 存档。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "安全说明：转账与红包只渲染卡片，不接入任何真实支付通道；" +
                            "所填的 API Key 仅保存在本机应用私有目录。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ── 行组件 ──────────────────────────────────────────────────────────────────

/** 读 AndroidManifest 里真正的 versionName，与构建产物保持一致。 */
private fun appVersionName(context: android.content.Context): String =
    runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().ifEmpty { "-" }

@Composable
private fun EndpointRow(
    endpoint: AiEndpoint,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    KithCard(onClick = onEdit) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            VendorMark(VendorRegistry.of(endpoint.vendor), size = 32.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(endpoint.label, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(6.dp))
                    Pill(if (endpoint.kind == EndpointKind.IMAGE) "生图" else "对话")
                    if (endpoint.apiKey.isEmpty()) {
                        Spacer(Modifier.width(4.dp))
                        Pill("缺 Key", MaterialTheme.colorScheme.error)
                    }
                }
                Text(
                    endpoint.baseUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { confirm = true }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("删除「${endpoint.label}」？") },
            text = { Text("挂在它下面的模型配置也会一起移除，使用这些模型的人物需要重新指定。") },
            confirmButton = {
                TextButton(onClick = { confirm = false; onDelete() }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun SavedModelRow(ref: ModelRef, onRemove: () -> Unit) {
    KithCard {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            VendorMark(VendorRegistry.of(ref.vendor), size = 28.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(ref.display(), style = MaterialTheme.typography.bodyMedium)
                Text(
                    ref.modelId,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "移除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    KithCard {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    desc,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun HintCard(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(16.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

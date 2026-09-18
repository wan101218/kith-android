package com.kith.app.ui.create

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kith.app.data.catalog.VendorRegistry
import com.kith.app.domain.EndpointKind
import com.kith.app.domain.ModelRef
import com.kith.app.domain.PlotTemplates
import com.kith.app.ui.character.CharacterEditorDialog
import com.kith.app.ui.common.CharacterAvatar
import com.kith.app.ui.common.GlassTopBar
import com.kith.app.ui.common.KithCard
import com.kith.app.ui.common.KithIcons
import com.kith.app.ui.common.KithIcon
import com.kith.app.ui.common.Pill
import com.kith.app.ui.common.VendorMark

/**
 * 创建社会。
 *
 * 一个社会就是一个独立项目：有名字、世界观、剧情走向、主要人物和自己的模型配置。
 * 创建完成后会落到 `filesDir/societies/{id}/` 一个独立文件夹里，与其他社会完全隔离。
 *
 * 表单状态全部放在 [CreateViewModel] 里 —— 因为模型配置改成了独立页面，
 * 用户会从这一页跳走再跳回来，`remember` 存的内容会在导航时被销毁。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateSocietyScreen(
    onNavigateToPicker: () -> Unit,
    onCreated: (String) -> Unit,
    onBack: () -> Unit,
    vm: CreateViewModel = viewModel(factory = CreateViewModel.factory()),
) {
    val draft = vm.characterDraft

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize()) {
            GlassTopBar(
                title = "新建社会",
                subtitle = "这一步定下的东西，会一直影响旁白怎么推进剧情",
                onBack = onBack,
                statusBarPadding = padding.calculateTopPadding() / 2,
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp, bottom = padding.calculateBottomPadding() + 28.dp),
            ) {
                // ── 名称与走向 ──────────────────────────────────────────────
                FieldLabel("社会名称", required = true)
                OutlinedTextField(
                    value = vm.name,
                    onValueChange = vm::updateName,
                    placeholder = { Text("如：南城旧事") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(18.dp))
                FieldLabel("剧情走向模板")
                Text(
                    "选一个贴近的模板会填好世界观与走向草稿，之后可以随意改。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PlotTemplates.all.forEach { t ->
                        FilterChip(
                            selected = vm.selectedTemplate.orientation == t.orientation,
                            onClick = { vm.applyTemplate(t) },
                            label = { Text(t.title) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(vm.orientation.label, MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        vm.orientation.hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // ── 世界观 ──────────────────────────────────────────────────
                Spacer(Modifier.height(18.dp))
                FieldLabel("世界观描述", required = true)
                OutlinedTextField(
                    value = vm.worldSetting,
                    onValueChange = vm::updateWorldSetting,
                    placeholder = { Text("这是个什么样的地方？规则是什么？人们怎么生活？") },
                    minLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )

                // ── 剧情走向 ────────────────────────────────────────────────
                Spacer(Modifier.height(18.dp))
                FieldLabel("剧情走向", required = true)
                OutlinedTextField(
                    value = vm.plotDirection,
                    onValueChange = vm::updatePlotDirection,
                    placeholder = { Text("你希望故事朝哪个方向走？节奏、重点、想避免的套路。") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(14.dp))
                FieldLabel("题材标签（可选，可多选）")
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    PlotTemplates.tropeSuggestions.forEach { t ->
                        val on = t in vm.tropes
                        FilterChip(
                            selected = on,
                            onClick = { vm.toggleTrope(t) },
                            label = { Text(t) },
                        )
                    }
                }

                // ── 主要人物 ────────────────────────────────────────────────
                Spacer(Modifier.height(22.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FieldLabel("主要人物", required = false, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.beginNewCharacter() }) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("添加")
                    }
                }
                Text(
                    "可以先只加主角，其余人物交给旁白按剧情自动生成；" +
                        "也可以在这里把关键角色一次配好。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                if (vm.characters.isEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .padding(20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "还没有人物。建议至少添加一个「你」扮演的主角，这样关系图才有中心。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        vm.characters.forEach { c ->
                            KithCard(onClick = { vm.beginEditCharacter(c) }) {
                                Row(
                                    Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CharacterAvatar(
                                        name = c.name,
                                        size = 42.dp,
                                        importance = c.importance,
                                        isUser = c.isUser,
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                c.name,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            if (c.isUser) {
                                                Spacer(Modifier.width(6.dp))
                                                Pill("你", MaterialTheme.colorScheme.secondary)
                                            }
                                        }
                                        Text(
                                            c.oneLiner.ifEmpty { c.personality }
                                                .ifEmpty { "尚未填写简介" },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                        if (c.tags.isNotEmpty()) {
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                c.tags.joinToString("、") { it.label },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.tertiary,
                                            )
                                        }
                                    }
                                    IconButton(onClick = { vm.removeCharacter(c) }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "移除",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ── 模型配置 ────────────────────────────────────────────────
                Spacer(Modifier.height(22.dp))
                FieldLabel("模型配置")
                Text(
                    "这三项可以留空，之后再配。但旁白没有模型就没法推进剧情。" +
                        "点任意一项会进入独立的配置页。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModelSlotRow(
                        title = "旁白模型",
                        desc = "负责推进剧情、生成 NPC、判断重要性",
                        ref = vm.narratorModel,
                        onClick = {
                            vm.requestPick(CreatePickTarget.NARRATOR)
                            onNavigateToPicker()
                        },
                    )
                    ModelSlotRow(
                        title = "人物默认模型",
                        desc = "新人物默认使用的模型，可被单独覆盖",
                        ref = vm.defaultCharacterModel,
                        onClick = {
                            vm.requestPick(CreatePickTarget.DEFAULT_CHARACTER)
                            onNavigateToPicker()
                        },
                    )
                    ModelSlotRow(
                        title = "文生图模型",
                        desc = "生成人物头像与聊天配图",
                        ref = vm.imageModel,
                        isImage = true,
                        onClick = {
                            vm.requestPick(CreatePickTarget.IMAGE)
                            onNavigateToPicker()
                        },
                    )
                }

                if (vm.error.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        vm.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(26.dp))
                Button(
                    onClick = { vm.create(onCreated) },
                    enabled = vm.canCreate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    if (vm.creating) {
                        CircularProgressIndicator(
                            Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Text("创建社会", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    // 人物编辑：草稿非空即视为打开。跳去选模型再回来，草稿仍在。
    if (draft != null) {
        CharacterEditorDialog(
            draft = draft,
            isExisting = vm.isEditingExistingDraft(),
            onChange = { updated -> vm.updateDraft { updated } },
            onPickModel = {
                vm.requestPick(CreatePickTarget.CHARACTER_DRAFT)
                onNavigateToPicker()
            },
            onSave = { vm.commitDraft() },
            onDismiss = { vm.cancelDraft() },
        )
    }
}

@Composable
private fun FieldLabel(
    text: String,
    required: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (required) {
            Text(
                " *",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun ModelSlotRow(
    title: String,
    desc: String,
    ref: ModelRef?,
    onClick: () -> Unit,
    isImage: Boolean = false,
) {
    KithCard(onClick = onClick) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (ref != null) {
                VendorMark(VendorRegistry.of(ref.vendor), size = 28.dp)
            } else {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    KithIcon(
                        if (isImage) KithIcons.ImageGen else KithIcons.Chip,
                        size = 16.dp,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    if (ref == null) {
                        Spacer(Modifier.width(6.dp))
                        Pill("未配置", MaterialTheme.colorScheme.error)
                    }
                }
                Text(
                    ref?.display() ?: desc,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                if (ref == null) "选择" else "更换",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

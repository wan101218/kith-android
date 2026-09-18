package com.kith.app.ui.character

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.kith.app.data.catalog.VendorRegistry
import com.kith.app.domain.Character
import com.kith.app.domain.CharacterTag
import com.kith.app.domain.Gender
import com.kith.app.domain.Importance
import com.kith.app.domain.TagTemplates
import com.kith.app.ui.common.CharacterAvatar
import com.kith.app.ui.common.KithIcons
import com.kith.app.ui.common.KithIcon
import com.kith.app.ui.common.VendorMark

/**
 * 人物编辑器。
 *
 * **完全由外部草稿驱动，自己不持有任何状态。**
 * 这一点是必须的：选模型已经改成独立页面，用户在编辑人物时点「选择模型」会跳走 ——
 * 如果这里用 `remember` 存字段，跳转会把编辑到一半的内容全部丢掉。
 * 现在草稿存在调用方的 ViewModel 里（创建社会用 `CreateViewModel`，
 * 人物管理用 `SocietyViewModel`），导航来回后界面会带着完整草稿重新显示。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CharacterEditorDialog(
    draft: Character,
    onChange: (Character) -> Unit,
    onPickModel: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    /** 是否已在列表里（决定按钮文案是「添加」还是「保存」） */
    isExisting: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isExisting) "编辑人物" else "添加人物") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // 头像预览
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CharacterAvatar(
                        name = draft.name.ifEmpty { "未命名" },
                        size = 56.dp,
                        importance = draft.importance,
                        isUser = draft.isUser,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("这是你扮演的主角", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(6.dp))
                            Switch(
                                checked = draft.isUser,
                                onCheckedChange = { onChange(draft.copy(isUser = it)) },
                            )
                        }
                        Text(
                            "关系图的中心节点。一个社会建议只设一个。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))

                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { onChange(draft.copy(name = it)) },
                    label = { Text("姓名 *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft.alias,
                    onValueChange = { onChange(draft.copy(alias = it)) },
                    label = { Text("别称 / 外号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))

                Text("性别", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Gender.entries.forEach { g ->
                        FilterChip(
                            selected = draft.gender == g,
                            onClick = { onChange(draft.copy(gender = g)) },
                            label = { Text(g.label) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft.age,
                    onValueChange = { onChange(draft.copy(age = it)) },
                    label = { Text("年龄") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft.oneLiner,
                    onValueChange = { onChange(draft.copy(oneLiner = it)) },
                    label = { Text("一句话概括") },
                    placeholder = { Text("如：表面吊儿郎当，其实什么都知道") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft.personality,
                    onValueChange = { onChange(draft.copy(personality = it)) },
                    label = { Text("性格特点") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft.background,
                    onValueChange = { onChange(draft.copy(background = it)) },
                    label = { Text("背景经历") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft.appearance,
                    onValueChange = { onChange(draft.copy(appearance = it)) },
                    label = { Text("外貌（也用于生成头像）") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft.speechStyle,
                    onValueChange = { onChange(draft.copy(speechStyle = it)) },
                    label = { Text("说话风格") },
                    placeholder = { Text("如：语速慢，爱用反问") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(16.dp))
                Text("重要性（决定用哪个档位的模型）", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Importance.entries.forEach { im ->
                        FilterChip(
                            selected = draft.importance == im,
                            onClick = { onChange(draft.copy(importance = im)) },
                            label = { Text("${im.label} · ${im.tier.label}") },
                        )
                    }
                }
                Text(
                    draft.importance.hint +
                        if (!draft.importance.chatEnabled) "（此档位只展示资料，不可发消息）" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "与「你」的关系标签",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (draft.tags.isNotEmpty()) {
                        TextButton(onClick = { onChange(draft.copy(tags = emptyList())) }) {
                            Text("清空")
                        }
                    }
                }
                Text(
                    "标签会直接决定 TA 怎么称呼你、保持什么距离、主动到什么程度。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    TagTemplates.all.forEach { t ->
                        val selected = draft.tags.any { it.label == t.label }
                        FilterChip(
                            selected = selected,
                            onClick = {
                                val tags = if (selected) {
                                    draft.tags.filterNot { it.label == t.label }
                                } else {
                                    draft.tags + CharacterTag(t.label, t.hint, true)
                                }
                                onChange(draft.copy(tags = tags))
                            },
                            label = { Text(t.label) },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("使用的模型", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .clickable { onPickModel() }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val ref = draft.model
                    if (ref != null) {
                        VendorMark(VendorRegistry.of(ref.vendor), size = 26.dp)
                    } else {
                        KithIcon(
                            KithIcons.Chip,
                            size = 22.dp,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            ref?.display() ?: "跟随社会默认模型",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            ref?.modelId ?: "未单独指定",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "选择",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (draft.model != null) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { onChange(draft.copy(model = null)) }) {
                        Text("改为跟随社会默认")
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = draft.name.isNotBlank()) {
                Text(if (isExisting) "保存" else "添加")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

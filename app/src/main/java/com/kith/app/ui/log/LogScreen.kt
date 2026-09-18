package com.kith.app.ui.log

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kith.app.ai.engine.Money
import com.kith.app.core.Ids
import com.kith.app.domain.LogEntry
import com.kith.app.domain.LogKind
import com.kith.app.kithGraph
import com.kith.app.ui.common.EmptyState
import com.kith.app.ui.common.GlassTopBar
import com.kith.app.ui.common.KithCard
import com.kith.app.ui.common.KithIcons
import com.kith.app.ui.common.Pill
import com.kith.app.ui.common.StatItem
import com.kith.app.ui.theme.color

/**
 * 日志页。
 *
 * 产品要求「包括旁白 AI 全部的模型所作所为和说了什么话必须有日志」，所以这里
 * 记录的是**每一次模型调用**：谁、用什么模型、发了什么、回了什么、花了多少。
 * 生图、视觉桥接、目录刷新也都在里面。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    societyId: String,
    onBack: () -> Unit,
) {
    var logs by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var filter by remember { mutableStateOf<LogKind?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(societyId) {
        logs = kithGraph.store.readLogs(societyId, 800)
        loading = false
    }

    val shown = if (filter == null) logs else logs.filter { it.kind == filter }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize()) {
            GlassTopBar(
                title = "日志",
                subtitle = "旁白与所有模型的每一次调用都会留痕",
                onBack = onBack,
                statusBarPadding = padding.calculateTopPadding() / 2,
                stats = listOf(
                    StatItem("条记录", logs.size.toString()),
                    StatItem("累计花费", Money.cny(logs.sumOf { it.costUsd })),
                    StatItem("总 Token", Money.tokenCount(logs.sumOf { it.tokensIn + it.tokensOut })),
                ),
            )

            FlowRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = filter == null,
                    onClick = { filter = null },
                    label = { Text("全部") },
                )
                LogKind.entries.forEach { k ->
                    val count = logs.count { it.kind == k }
                    if (count == 0) return@forEach
                    FilterChip(
                        selected = filter == k,
                        onClick = { filter = if (filter == k) null else k },
                        label = { Text("${k.label} $count") },
                    )
                }
            }

            if (!loading && shown.isEmpty()) {
                EmptyState(
                    iconRes = KithIcons.Log,
                    title = "还没有日志",
                    subtitle = "让旁白推进剧情，或者找个人聊两句，这里就会记下来。",
                )
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        bottom = padding.calculateBottomPadding() + 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.id }) { entry ->
                        LogRow(entry)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogRow(entry: LogEntry) {
    var expanded by remember { mutableStateOf(false) }
    val accent = entry.kind.color()

    KithCard(onClick = { expanded = !expanded }) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
                Spacer(Modifier.width(8.dp))
                Pill(entry.kind.label, accent)
                Spacer(Modifier.width(8.dp))
                Text(
                    entry.actor,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    Ids.clock(entry.ts),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                entry.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (entry.detail.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    entry.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) 40 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val meta = buildList {
                if (entry.modelLabel.isNotEmpty()) add(entry.modelLabel)
                if (entry.tokensIn + entry.tokensOut > 0) {
                    add("${entry.tokensIn}→${entry.tokensOut} tok")
                }
                if (entry.costUsd > 0) add(Money.cny(entry.costUsd))
            }
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    meta.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
            }
        }
    }
}

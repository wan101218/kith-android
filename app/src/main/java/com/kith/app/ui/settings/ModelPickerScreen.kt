package com.kith.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kith.app.data.catalog.VendorRegistry
import com.kith.app.data.pick.PickRequest
import com.kith.app.domain.AiEndpoint
import com.kith.app.domain.EndpointKind
import com.kith.app.domain.ModelRef
import com.kith.app.kithGraph
import com.kith.app.ui.common.EmptyState
import com.kith.app.ui.common.GlassTopBar
import com.kith.app.ui.common.KithCard
import com.kith.app.ui.common.KithIcons
import com.kith.app.ui.common.KithIcon
import com.kith.app.ui.common.Pill
import com.kith.app.ui.common.SectionHeader
import com.kith.app.ui.common.VendorMark

/**
 * 选模型 · 第一步：选接入点。
 *
 * 从原来的底部面板改成独立全屏页 —— 模型配置这件事信息量不小
 * （已保存的配置、多个接入点、各自的 Key 状态、还要能新增接入点），
 * 挤在半屏面板里每一步都要滚动，独立页面能把层级铺开。
 *
 * 后续步骤：
 *   本页 → [ModelListScreen]（在该厂商下挑具体模型）→ 回传给发起方
 */
@Composable
fun ModelPickerScreen(
    request: PickRequest,
    onPickEndpoint: (AiEndpoint) -> Unit,
    onPickedSaved: (ModelRef) -> Unit,
    onManageEndpoints: () -> Unit,
    onBack: () -> Unit,
) {
    val graph = kithGraph
    val settings by graph.settings.state.collectAsStateWithLifecycle()

    val endpoints = settings.endpoints.filter { it.kind == request.kind }
    val saved = settings.savedModels.filter { ref -> endpoints.any { it.id == ref.endpointId } }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize()) {
            GlassTopBar(
                title = "选择模型",
                subtitle = request.title,
                onBack = onBack,
                statusBarPadding = padding.calculateTopPadding() / 2,
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = padding.calculateBottomPadding() + 28.dp),
            ) {
                if (request.subtitle.isNotEmpty()) {
                    Text(
                        request.subtitle,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // ── 已保存的配置：一步复用 ──
                if (saved.isNotEmpty()) {
                    SectionHeader("已保存的模型配置")
                    Column(
                        Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        saved.forEach { ref ->
                            KithCard(onClick = { onPickedSaved(ref) }) {
                                Row(
                                    Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    VendorMark(VendorRegistry.of(ref.vendor), size = 30.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            ref.display(),
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        Text(
                                            ref.modelId,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Pill("直接使用", MaterialTheme.colorScheme.tertiary)
                                }
                            }
                        }
                    }
                }

                // ── 按接入点挑选 ──
                if (endpoints.isEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    EmptyState(
                        iconRes = KithIcons.Chip,
                        title = "还没有${request.kind.label}接入点",
                        subtitle = "模型需要通过接入点调用。先添加一个，把厂商和 API Key 填进来，" +
                            "之后就能在它的模型列表里挑了。",
                        action = {
                            Button(onClick = onManageEndpoints) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("添加接入点")
                            }
                        },
                    )
                } else {
                    SectionHeader("按接入点挑选")
                    Column(
                        Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        endpoints.forEach { ep ->
                            EndpointPickRow(endpoint = ep) { onPickEndpoint(ep) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        Button(
                            onClick = onManageEndpoints,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("添加接入点")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EndpointPickRow(endpoint: AiEndpoint, onClick: () -> Unit) {
    KithCard(onClick = onClick) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            VendorMark(VendorRegistry.of(endpoint.vendor), size = 34.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(endpoint.label, style = MaterialTheme.typography.titleSmall)
                    if (endpoint.apiKey.isEmpty()) {
                        Spacer(Modifier.width(6.dp))
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
            Text(
                "挑模型",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

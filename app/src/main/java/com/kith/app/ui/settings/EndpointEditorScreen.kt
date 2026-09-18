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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kith.app.ai.Http
import com.kith.app.core.Ids
import com.kith.app.data.catalog.VendorRegistry
import com.kith.app.domain.AiEndpoint
import com.kith.app.domain.EndpointKind
import com.kith.app.ui.common.GlassTopBar
import com.kith.app.ui.common.KithCard
import com.kith.app.ui.common.Pill
import com.kith.app.ui.common.VendorMark
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 接入点新增/编辑（独立全屏页）。
 *
 * 从弹窗改成页面后能放下的东西变多了：厂商列表可以直接铺开搜索而不是再套一层弹窗，
 * 并且能加「测试连接」—— 这个按钮在弹窗里根本没地方放，但它恰恰是配置代理/中转时
 * 最需要的能力（地址填错了当场就能发现，不用等聊到一半才报 404）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EndpointEditorScreen(
    kind: EndpointKind,
    existing: AiEndpoint? = null,
    onSaved: (AiEndpoint) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var vendorKey by remember { mutableStateOf(existing?.vendor ?: "DeepSeek") }
    var label by remember { mutableStateOf(existing?.label.orEmpty()) }
    var baseUrl by remember {
        mutableStateOf(
            existing?.baseUrl?.takeIf { it.isNotEmpty() } ?: VendorRegistry.of("DeepSeek").baseUrl,
        )
    }
    var apiKey by remember { mutableStateOf(existing?.apiKey.orEmpty()) }
    var vendorListOpen by remember { mutableStateOf(existing == null) }
    var vendorKeyword by remember { mutableStateOf("") }

    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    val vendor = VendorRegistry.of(vendorKey)

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize()) {
            GlassTopBar(
                title = if (existing == null) "添加接入点" else "编辑接入点",
                subtitle = "${kind.label} · Key 只存在本机",
                onBack = onBack,
                statusBarPadding = padding.calculateTopPadding() / 2,
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = padding.calculateBottomPadding() + 28.dp),
            ) {
                // ── 厂商 ──
                FieldLabel("厂商")
                KithCard(onClick = { vendorListOpen = !vendorListOpen }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        VendorMark(vendor, size = 32.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(vendor.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (vendor.baseUrl.isNotEmpty()) vendor.baseUrl else "需自行填写接入地址",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (vendor.domestic) {
                            Pill("国内直连", MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            if (vendorListOpen) "收起" else "更换",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                if (vendorListOpen) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = vendorKeyword,
                        onValueChange = { vendorKeyword = it },
                        placeholder = { Text("搜索厂商") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    val list = VendorRegistry.all.filter {
                        vendorKeyword.isBlank() ||
                            it.name.contains(vendorKeyword, true) ||
                            it.key.contains(vendorKeyword, true)
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.medium,
                        border = androidx.compose.foundation.BorderStroke(
                            0.8.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                            list.forEach { v ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            vendorKey = v.key
                                            if (v.baseUrl.isNotEmpty()) baseUrl = v.baseUrl
                                            if (label.isBlank()) label = "${v.name} 主号"
                                            vendorListOpen = false
                                            testResult = null
                                        }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    VendorMark(v, size = 28.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(v.name, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            if (v.baseUrl.isNotEmpty()) v.baseUrl else "需自行填写",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (v.key == vendorKey) {
                                        Pill("当前", MaterialTheme.colorScheme.primary, filled = true)
                                    }
                                }
                            }
                        }
                    }
                }

                // ── 字段 ──
                Spacer(Modifier.height(18.dp))
                FieldLabel("备注名")
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    placeholder = { Text("${vendor.name} 主号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(14.dp))
                FieldLabel("接入地址（OpenAI 兼容）")
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = {
                        baseUrl = it
                        testResult = null
                    },
                    placeholder = { Text("https://api.example.com/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(14.dp))
                FieldLabel("API Key")
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        testResult = null
                    },
                    placeholder = { Text("粘贴你的 Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    trailingIcon = if (apiKey.isNotEmpty()) {
                        {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "清空",
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { apiKey = "" },
                            )
                        }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )

                // ── 测试连接 ──
                Spacer(Modifier.height(18.dp))
                OutlinedButton(
                    onClick = {
                        testing = true
                        testResult = null
                        scope.launch {
                            testResult = runCatching {
                                val url = baseUrl.trim().trimEnd('/') + "/models"
                                val resp = Http.getText(
                                    url,
                                    headers = if (apiKey.isBlank()) emptyMap() else {
                                        mapOf("Authorization" to "Bearer ${apiKey.trim()}")
                                    },
                                )
                                if (!resp.ok) {
                                    false to "HTTP ${resp.code}：${resp.body.take(160)}"
                                } else {
                                    val n = runCatching {
                                        JSONObject(resp.body).optJSONArray("data")?.length() ?: 0
                                    }.getOrDefault(0)
                                    if (n > 0) {
                                        true to "连接正常，该接入点报告了 $n 个可用模型"
                                    } else {
                                        true to "连接正常（该服务未返回模型列表，属正常情况）"
                                    }
                                }
                            }.getOrElse { false to (it.message ?: it.javaClass.simpleName) }
                            testing = false
                        }
                    },
                    enabled = !testing && baseUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (testing) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("测试连接")
                }

                testResult?.let { (ok, msg) ->
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = if (ok) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        },
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            msg,
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ok) {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            },
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    "Key 只保存在本机应用私有目录，不会上传到任何服务器。" +
                        "「测试连接」只会请求该地址的 /models 接口。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(26.dp))
                Button(
                    onClick = {
                        onSaved(
                            AiEndpoint(
                                id = existing?.id ?: Ids.new("ep"),
                                label = label.ifBlank { "${vendor.name} 主号" },
                                vendor = vendor.key,
                                kind = kind,
                                baseUrl = baseUrl.trim().trimEnd('/'),
                                apiKey = apiKey.trim(),
                                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                            ),
                        )
                    },
                    enabled = baseUrl.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Text("保存", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(6.dp))
}

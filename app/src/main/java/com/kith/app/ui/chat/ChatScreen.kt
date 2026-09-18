package com.kith.app.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.kith.app.ai.engine.Money
import com.kith.app.data.sticker.StickerResolution
import com.kith.app.domain.ChatMessage
import com.kith.app.domain.MsgRole
import com.kith.app.domain.MsgStatus
import com.kith.app.domain.PictureState
import com.kith.app.domain.Segment
import com.kith.app.ui.common.CharacterAvatar
import com.kith.app.ui.common.GlassTopBar
import com.kith.app.ui.common.KithIcons
import com.kith.app.ui.common.KithIcon
import com.kith.app.ui.common.Pill
import com.kith.app.ui.common.StatItem
import com.kith.app.ui.common.TypingDots
import com.kith.app.ui.theme.LocalKithColors
import kotlinx.coroutines.launch
import java.io.File

/**
 * 与一个人物的独立会话。
 *
 * 渲染上完整实现了 Human Chat Protocol：文本、表情包、图片、转账四种片段按
 * 模型输出的原始顺序拼成一条消息流。
 */
@Composable
fun ChatScreen(
    societyId: String,
    charId: String,
    onBack: () -> Unit,
    /** 跳到资源库管理页（表情包库 / 审核模型文件统一在那里管理） */
    onNavigateToLibrary: () -> Unit = {},
    vm: ChatViewModel = viewModel(
        key = "$societyId/$charId",
        factory = ChatViewModel.factory(societyId, charId),
    ),
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val colors = LocalKithColors.current
    val graph = com.kith.app.kithGraph

    var input by remember { mutableStateOf("") }
    var pendingImage by remember { mutableStateOf("") }
    var stickerPanelOpen by remember { mutableStateOf(false) }
    var transferDialogOpen by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull() ?: return@launch
            val ext = context.contentResolver.getType(uri)?.substringAfterLast('/') ?: "jpg"
            val f = graph.store.saveMedia(societyId, bytes, if (ext == "jpeg") "jpg" else ext)
            pendingImage = f.absolutePath
        }
    }

    // 新消息自动滚到底
    LaunchedEffect(state.messages.size, state.streaming?.raw) {
        val total = state.messages.size + if (state.streaming != null) 1 else 0
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    val partner = state.partner

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            GlassTopBar(
                title = partner?.name ?: "载入中",
                subtitle = buildString {
                    partner?.let {
                        append(it.importance.label)
                        if (it.tags.isNotEmpty()) append(" · ${it.tags.joinToString("、") { t -> t.label }}")
                    }
                },
                onBack = onBack,
                statusBarPadding = padding.calculateTopPadding() / 2,
                stats = listOf(
                    StatItem("本会话花费", Money.cny(state.sessionCostUsd)),
                    StatItem(
                        "模型",
                        partner?.model?.label?.take(14)
                            ?: state.bundle?.society?.defaultCharacterModel?.label?.take(14)
                            ?: "未配置",
                    ),
                ),
                actions = {
                    if (state.generating) {
                        Box(Modifier.padding(end = 12.dp)) { TypingDots() }
                    }
                },
            )

            if (state.notice.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable { vm.clearNotice() },
                ) {
                    Text(
                        state.notice,
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            // ── 消息流 ──
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 14.dp,
                    vertical = 10.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.messages, key = { it.id }) { m ->
                    MessageRow(
                        message = m,
                        partnerName = partner?.name.orEmpty(),
                        partnerAvatar = partner?.avatarUrl.orEmpty(),
                        resolveSticker = { vm.resolveSticker(it) },
                        onConfirmTransfer = { vm.confirmTransfer(m.id, it) },
                    )
                }
                state.streaming?.let { s ->
                    item(key = "streaming") {
                        MessageRow(
                            message = s,
                            partnerName = partner?.name.orEmpty(),
                            partnerAvatar = partner?.avatarUrl.orEmpty(),
                            resolveSticker = { vm.resolveSticker(it) },
                            onConfirmTransfer = {},
                            streaming = true,
                        )
                    }
                }
            }

            // ── 输入区 ──
            Surface(
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(0.6.dp, colors.glassBorder),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 8.dp,
                            end = 8.dp,
                            top = 8.dp,
                            bottom = padding.calculateBottomPadding() + 8.dp,
                        ),
                ) {
                    if (pendingImage.isNotEmpty()) {
                        Row(
                            Modifier.padding(start = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(
                                model = File(pendingImage),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(MaterialTheme.shapes.small),
                                contentScale = ContentScale.Crop,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "这张图会一起发出去",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { pendingImage = "" }) {
                                Icon(Icons.Default.Add, contentDescription = "移除", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    // ── 表情面板 ──
                    // 系统情绪词直达 + 本社会的专属表情库 + 管理入口。
                    // 库是空的时给引导，把用户带去资源库管理页添加。
                    if (stickerPanelOpen) {
                        val lib = state.stickerLibrary
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 230.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        ) {
                            Text(
                                "常用情绪",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                com.kith.app.ai.prompt.Prompts.EMOTION_WORDS.take(14).forEach { word ->
                                    Surface(
                                        shape = RoundedCornerShape(999.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier.clickable {
                                            stickerPanelOpen = false
                                            vm.sendSticker(emotion = word)
                                        },
                                    ) {
                                        Text(
                                            word,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(10.dp))
                            Text(
                                "专属表情包（${lib.size}）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            if (lib.isEmpty()) {
                                Text(
                                    "还没有专属表情",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                lib.chunked(5).forEach { rowItems ->
                                    Row(Modifier.padding(vertical = 3.dp)) {
                                        rowItems.forEach { s ->
                                            AsyncImage(
                                                model = if (s.imageUrl.startsWith("/")) File(s.imageUrl) else s.imageUrl,
                                                contentDescription = s.name,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(52.dp)
                                                    .clip(MaterialTheme.shapes.small)
                                                    .clickable {
                                                        stickerPanelOpen = false
                                                        vm.sendSticker(
                                                            emotion = s.emotions.firstOrNull().orEmpty(),
                                                            stickerId = s.id,
                                                        )
                                                    },
                                                contentScale = ContentScale.Fit,
                                            )
                                        }
                                        repeat(5 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = onNavigateToLibrary) {
                                    KithIcon(KithIcons.Sticker, size = 15.dp)
                                    Spacer(Modifier.width(4.dp))
                                    Text("管理表情包")
                                }
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { stickerPanelOpen = false }) {
                                    Text("收起")
                                }
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.Bottom) {
                        IconButton(onClick = { pickImage.launch("image/*") }) {
                            KithIcon(
                                KithIcons.ImageGen,
                                contentDescription = "发图",
                                size = 22.dp,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { stickerPanelOpen = !stickerPanelOpen }) {
                            KithIcon(
                                KithIcons.Sticker,
                                contentDescription = "表情",
                                size = 22.dp,
                                tint = if (stickerPanelOpen) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            placeholder = { Text("说点什么…") },
                            maxLines = 4,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(6.dp))
                        IconButton(onClick = { transferDialogOpen = true }) {
                            KithIcon(
                                KithIcons.Transfer,
                                contentDescription = "转账",
                                size = 22.dp,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = {
                                val t = input
                                val img = pendingImage
                                input = ""
                                pendingImage = ""
                                vm.send(t, img)
                            },
                            enabled = !state.generating && (input.isNotBlank() || pendingImage.isNotEmpty()),
                        ) {
                            Icon(
                                Icons.Default.Send,
                                contentDescription = "发送",
                                tint = if (!state.generating && (input.isNotBlank() || pendingImage.isNotEmpty())) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // ── 转账对话框（模拟支付，不接任何真实通道）──
    if (transferDialogOpen) {
        var amountText by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        val amount = amountText.toDoubleOrNull()
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { transferDialogOpen = false },
            title = { Text("给「${state.partner?.name ?: ""}」转账") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("金额（元）") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                        ),
                        isError = amountText.isNotEmpty() && (amount == null || amount <= 0.0),
                        supportingText = {
                            if (amountText.isNotEmpty() && (amount == null || amount <= 0.0)) {
                                Text("请输入大于 0 的金额")
                            }
                        },
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("备注（可选）") },
                        singleLine = true,
                    )
                    Text(
                        "模拟转账，不产生真实扣款",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = amount != null && amount > 0.0,
                    onClick = {
                        vm.sendTransfer(amount ?: 0.0, note)
                        transferDialogOpen = false
                        stickerPanelOpen = false
                    },
                ) { Text("塞进红包") }
            },
            dismissButton = {
                TextButton(onClick = { transferDialogOpen = false }) { Text("取消") }
            },
        )
    }
}

// ── 消息渲染 ────────────────────────────────────────────────────────────────

@Composable
private fun MessageRow(
    message: ChatMessage,
    partnerName: String,
    partnerAvatar: String,
    resolveSticker: (Segment.Sticker) -> StickerResolution,
    onConfirmTransfer: (Segment.Transfer) -> Unit,
    streaming: Boolean = false,
) {
    val colors = LocalKithColors.current
    val isMe = message.role == MsgRole.USER
    val isNarrator = message.role == MsgRole.NARRATOR
    val isSystem = message.role == MsgRole.SYSTEM

    if (isNarrator || isSystem) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                color = if (isNarrator) colors.narratorSurface else MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    if (isNarrator) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            KithIcon(KithIcons.Narrator, size = 14.dp, tint = colors.narratorAccent)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "旁白",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.narratorAccent,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(
                        message.plainText(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isNarrator) colors.narratorAccent.copy(alpha = 0.95f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        return
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!isMe) {
            CharacterAvatar(name = partnerName.ifEmpty { "?" }, size = 34.dp, avatarUrl = partnerAvatar)
            Spacer(Modifier.width(8.dp))
        }

        Column(
            horizontalAlignment = if (isMe) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            if (message.attachment.isNotEmpty() && isMe) {
                BubbledMedia(
                    path = message.attachment,
                    isMe = true,
                    caption = "",
                )
                Spacer(Modifier.height(6.dp))
            }

            message.segments.forEach { seg ->
                when (seg) {
                    is Segment.Text -> {
                        val text = seg.content.trim()
                        if (text.isNotEmpty()) {
                            Surface(
                                color = if (isMe) colors.bubbleMe else colors.bubbleThem,
                                shape = RoundedCornerShape(
                                    topStart = 16.dp, topEnd = 16.dp,
                                    bottomStart = if (isMe) 16.dp else 4.dp,
                                    bottomEnd = if (isMe) 4.dp else 16.dp,
                                ),
                            ) {
                                Text(
                                    text,
                                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isMe) colors.onBubbleMe else colors.onBubbleThem,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }

                    is Segment.Sticker -> {
                        when (val res = resolveSticker(seg)) {
                            is StickerResolution.Emoji -> {
                                // emoji 走系统字体渲染，观感和真表情一致，且不需要任何图片资源
                                Text(res.glyph, fontSize = 46.sp)
                                Spacer(Modifier.height(2.dp))
                            }
                            is StickerResolution.Image -> {
                                AsyncImage(
                                    model = if (res.sticker.imageUrl.startsWith("/")) {
                                        File(res.sticker.imageUrl)
                                    } else {
                                        res.sticker.imageUrl
                                    },
                                    contentDescription = res.sticker.name,
                                    modifier = Modifier
                                        .widthIn(max = 130.dp)
                                        .clip(MaterialTheme.shapes.small),
                                    contentScale = ContentScale.Fit,
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }

                    is Segment.Picture -> {
                        PictureSegmentView(seg, isMe)
                        Spacer(Modifier.height(4.dp))
                    }

                    is Segment.Transfer -> {
                        TransferCard(
                            transfer = seg,
                            sentByMe = isMe,
                            onConfirm = onConfirmTransfer,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }

            if (message.status == MsgStatus.FAILED && message.error.isNotEmpty()) {
                Text(
                    "发送失败：${message.error}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (!streaming && message.costUsd > 0) {
                Text(
                    "${message.modelLabel} · ${Money.cny(message.costUsd)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun BubbledMedia(path: String, isMe: Boolean, caption: String) {
    val colors = LocalKithColors.current
    Surface(
        color = if (isMe) colors.bubbleMe else colors.bubbleThem,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(4.dp)) {
            AsyncImage(
                model = if (path.startsWith("/")) File(path) else path,
                contentDescription = null,
                modifier = Modifier
                    .widthIn(max = 200.dp)
                    .clip(MaterialTheme.shapes.small),
                contentScale = ContentScale.Fit,
            )
            if (caption.isNotEmpty()) {
                Text(
                    caption,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isMe) colors.onBubbleMe.copy(alpha = 0.8f) else colors.onBubbleThem.copy(alpha = 0.8f),
                )
            }
        }
    }
}

/**
 * 图片片段。
 *
 * 三种状态分别渲染：生成中的转圈占位、成功后的图片、失败后的原因提示 ——
 * 对应 protocol-spec 里「生图是异步的，不能阻塞 AI 的回复流」这条要求。
 */
@Composable
private fun PictureSegmentView(pic: Segment.Picture, isMe: Boolean) {
    when {
        pic.state == PictureState.READY && pic.url.isNotEmpty() ->
            BubbledMedia(pic.url, isMe, pic.caption)

        pic.state == PictureState.GENERATING -> {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("画画中…", style = MaterialTheme.typography.bodySmall)
                        if (pic.prompt.isNotEmpty()) {
                            Text(
                                pic.prompt.take(40),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        else -> {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "图片没能生成",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    if (pic.caption.isNotEmpty()) {
                        Text(
                            pic.caption,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 转账卡片。
 *
 * **安全边界**：只渲染卡片，绝不自动执行任何支付。用户点「收下」后也只是把
 * 卡片标记为已确认并记一条日志 —— 这是模拟玩法，不接任何真实资金通道。
 */
@Composable
private fun TransferCard(
    transfer: Segment.Transfer,
    /** 自己发的转账不需要「收下」按钮，状态文案也不同 */
    sentByMe: Boolean = false,
    onConfirm: (Segment.Transfer) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.secondary
    Surface(
        color = accent.copy(alpha = 0.10f),
        shape = MaterialTheme.shapes.medium,
        border = androidx.compose.foundation.BorderStroke(0.8.dp, accent.copy(alpha = 0.35f)),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KithIcon(KithIcons.Transfer, size = 26.dp, tint = accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "¥%.2f".format(transfer.amount),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
                Text(
                    "给 ${transfer.to}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (transfer.note.isNotEmpty()) {
                    Text(
                        transfer.note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (transfer.confirmed || sentByMe) {
                Pill(if (sentByMe) "已发出" else "已收下", MaterialTheme.colorScheme.tertiary, filled = true)
            } else {
                Surface(
                    color = accent,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.clickable { onConfirm(transfer) },
                ) {
                    Text(
                        "收下",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondary,
                    )
                }
            }
        }
    }
}

package com.kith.app.ui.common

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kith.app.R
import com.kith.app.core.Ids
import com.kith.app.data.catalog.VendorInfo
import com.kith.app.domain.Importance
import com.kith.app.ui.theme.LocalKithColors
import com.kith.app.ui.theme.color
import java.io.File

/** 项目自绘图标资源索引。 */
object KithIcons {
    val Tree = R.drawable.kith_ic_tree
    val Chain = R.drawable.kith_ic_chain
    val Narrator = R.drawable.kith_ic_narrator
    val Spark = R.drawable.kith_ic_spark
    val SparkSmall = R.drawable.kith_ic_spark_small
    val Sticker = R.drawable.kith_ic_sticker
    val Transfer = R.drawable.kith_ic_transfer
    val Import = R.drawable.kith_ic_import
    val Export = R.drawable.kith_ic_export
    val Tag = R.drawable.kith_ic_tag
    val Log = R.drawable.kith_ic_log
    val Vision = R.drawable.kith_ic_vision
    val Chip = R.drawable.kith_ic_chip
    val ImageGen = R.drawable.kith_ic_imagegen
    val People = R.drawable.kith_ic_people
    val GodView = R.drawable.kith_ic_godview
    val Dice = R.drawable.kith_ic_dice
    val Globe = R.drawable.kith_ic_globe
    val Lock = R.drawable.kith_ic_lock
    val Flask = R.drawable.kith_ic_flask
}

@Composable
fun KithIcon(
    res: Int,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    size: Dp = 20.dp,
) {
    Icon(
        painter = painterResource(res),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = tint,
    )
}

/**
 * 厂商标识。
 *
 * 用「品牌色圆角磁贴 + 字母徽记」而不是各家商标位图 ——
 * 20 多个图标风格无法统一，反而破坏界面一致性；而且这套做法能覆盖任意新厂商
 * （包括用户手填的），颜色由名称哈希派生，风格自动对齐。
 */
/**
 * 按底色亮度挑可读的前景色：浅底（琥珀、薰衣草紫、浅黄品牌色等）给墨色字，
 * 深底给白字。深浅两套主题下浅色容器底都可能出现，写死 White 会在深色
 * 模式的浅色强调底上不可读。
 */
internal fun onReadable(bg: Color): Color =
    if (bg.luminance() > 0.55f) Color(0xFF1C1F29) else Color.White

@Composable
fun VendorMark(
    vendor: VendorInfo,
    size: Dp = 30.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.30f))
            .background(vendor.color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = vendor.monogram,
            color = onReadable(vendor.color),
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.40f).sp,
            maxLines = 1,
        )
    }
}

/**
 * 人物头像。
 *
 * 有图就用图（用户上传或文生图产出），没图就用「姓名首字 + 稳定渐变」生成一个 ——
 * 关键点是**同一个名字永远得到同一套配色**（种子取名字的哈希），
 * 这样用户在关系图和聊天里看到的是同一个视觉身份，不会认错人。
 */
@Composable
fun CharacterAvatar(
    name: String,
    size: Dp = 44.dp,
    avatarUrl: String = "",
    importance: Importance? = null,
    isUser: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = LocalKithColors.current
    val seed = Ids.stableSeed(name)

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        // 重要性光环
        if (importance != null && importance != Importance.EXTRA) {
            val ringColor = if (isUser) colors.selfNode else importance.color()
            Box(
                Modifier
                    .size(size)
                    .border(width = if (isUser) 2.4.dp else 1.8.dp, color = ringColor.copy(alpha = 0.85f), shape = CircleShape),
            )
        }

        val inner = size - 6.dp
        if (avatarUrl.isNotEmpty()) {
            AsyncImage(
                model = if (avatarUrl.startsWith("/")) File(avatarUrl) else avatarUrl,
                contentDescription = name,
                modifier = Modifier
                    .size(inner)
                    .clip(CircleShape),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        } else {
            val hue = (seed % 360).toFloat()
            Box(
                modifier = Modifier
                    .size(inner)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                hsl(hue, 0.52f, 0.62f),
                                hsl((hue + 38f) % 360f, 0.55f, 0.46f),
                            ),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = Ids.initials(name),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (inner.value * 0.40f).sp,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun hsl(h: Float, s: Float, l: Float): Color {
    val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
    val hp = h / 60f
    val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
    val (r1, g1, b1) = when (hp.toInt()) {
        0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
    }
    val m = l - c / 2f
    return Color(
        (r1 + m).coerceIn(0f, 1f),
        (g1 + m).coerceIn(0f, 1f),
        (b1 + m).coerceIn(0f, 1f),
    )
}

// ── 毛玻璃顶栏 ──────────────────────────────────────────────────────────────

/** 顶栏上的一个数据点。 */
data class StatItem(val label: String, val value: String, val accent: Color? = null)

/**
 * 顶部状态栏。
 *
 * 产品明确要求「状态栏要透明，还要显示详细数据」。做法是把内容真正画在系统状态栏
 * 下方、内容之上，并用 `Modifier.blur` 做真实的高斯模糊（minSdk 33 可直接用），
 * 滚动时下方内容会透出来糊开，形成毛玻璃层次。
 */
@Composable
fun GlassTopBar(
    title: String,
    subtitle: String? = null,
    stats: List<StatItem> = emptyList(),
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    statusBarPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val colors = LocalKithColors.current
    Box(modifier = modifier.fillMaxWidth()) {
        // 模糊层：把底下的内容糊掉，自己做底
        Box(
            Modifier
                .matchParentSize()
                .blur(18.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = statusBarPadding + 6.dp, bottom = 10.dp)
                .padding(horizontal = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                } else {
                    Spacer(Modifier.width(12.dp))
                }

                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrEmpty()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                actions()
            }

            if (stats.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    stats.forEach { s ->
                        Column {
                            Text(
                                text = s.value,
                                style = MaterialTheme.typography.titleSmall,
                                color = s.accent ?: MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                            )
                            Text(
                                text = s.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        // 底部一条极淡的分隔线，滚动时给内容一个边界
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(0.6.dp)
                .background(colors.glassBorder),
        )
    }
}

// ── 通用小件 ────────────────────────────────────────────────────────────────

@Composable
fun Pill(
    text: String,
    color: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .then(
                if (filled) Modifier.background(color)
                else Modifier.background(color.copy(alpha = 0.12f)),
            )
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (filled) onReadable(color) else color,
            maxLines = 1,
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
fun EmptyState(
    iconRes: Int,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            KithIcon(
                res = iconRes,
                size = 32.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/**
 * 「思考中」的三点动画。
 * 用自绘 Canvas 而不是三个 Text 点，避免字体渲染出的点大小不一。
 */
@Composable
fun TypingDots(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "typing")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing),
        ),
        label = "phase",
    )
    Canvas(modifier = modifier.size(width = 26.dp, height = 10.dp)) {
        val r = size.height * 0.16f
        val gap = size.width / 3.4f
        repeat(3) { i ->
            val active = (phase.value.toInt() % 3) == i
            drawCircle(
                color = color.copy(alpha = if (active) 1f else 0.35f),
                radius = if (active) r * 1.25f else r,
                center = Offset(gap * (i + 0.7f), size.height / 2f),
            )
        }
    }
}

/** 统一的卡片容器。 */
@Composable
fun KithCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(0.8.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        content()
    }
}

/** 让文本列表项保持统一的左右内边距。 */
val ScreenPadding = PaddingValues(horizontal = 20.dp)

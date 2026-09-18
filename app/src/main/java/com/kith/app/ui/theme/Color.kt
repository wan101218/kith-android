package com.kith.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.kith.app.domain.Importance
import com.kith.app.domain.LogKind

// ── 品牌基色 ────────────────────────────────────────────────────────────────

/** 墨黑：主界面文字与主按钮 */
val Ink = Color(0xFF1C1F29)
val InkDeep = Color(0xFF12131A)

/** 琥珀：唯一强调色，只用在「你」「当前」「重要」这些真正需要被看到的地方 */
val Amber = Color(0xFFF5B544)
val AmberDeep = Color(0xFFC9820F)
val AmberSoft = Color(0xFFFDEFD2)

/** 紫罗兰：关系与标签体系的辅助色 */
val Violet = Color(0xFF6B5BD2)
val VioletSoft = Color(0xFFEDEAFF)

/** 青绿：旁观 / 上帝视角 */
val Teal = Color(0xFF1F8A80)
val TealSoft = Color(0xFFDCF2F0)

val Rose = Color(0xFFD25570)
val RoseSoft = Color(0xFFFCE8ED)

// ── Material3 配色方案 ──────────────────────────────────────────────────────

internal val KithLightScheme = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2A2F3D),
    onPrimaryContainer = Color(0xFFEDEFF5),

    secondary = AmberDeep,
    onSecondary = Color.White,
    secondaryContainer = AmberSoft,
    onSecondaryContainer = Color(0xFF4A3403),

    tertiary = Violet,
    onTertiary = Color.White,
    tertiaryContainer = VioletSoft,
    onTertiaryContainer = Color(0xFF241C66),

    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF14161C),

    surface = Color.White,
    onSurface = Color(0xFF14161C),
    surfaceVariant = Color(0xFFEEF0F4),
    onSurfaceVariant = Color(0xFF5A6070),

    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFBFD),
    surfaceContainer = Color(0xFFF4F5F8),
    surfaceContainerHigh = Color(0xFFEEF0F4),
    surfaceContainerHighest = Color(0xFFE8EBF0),

    outline = Color(0xFFD5D9E0),
    outlineVariant = Color(0xFFE7EAF0),

    error = Color(0xFFC0392B),
    onError = Color.White,
    errorContainer = Color(0xFFFBE4E1),
    onErrorContainer = Color(0xFF5C1710),

    scrim = Color(0x99000000),
)

internal val KithDarkScheme = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF221A08),
    primaryContainer = Color(0xFF4A3A12),
    onPrimaryContainer = Color(0xFFFBE3B4),

    secondary = Color(0xFFC7CEDA),
    onSecondary = Color(0xFF1A1D24),
    secondaryContainer = Color(0xFF32373F),
    onSecondaryContainer = Color(0xFFE3E7EE),

    tertiary = Color(0xFFA99BFF),
    onTertiary = Color(0xFF1E1746),
    tertiaryContainer = Color(0xFF362C7A),
    onTertiaryContainer = Color(0xFFE6E1FF),

    background = InkDeep,
    onBackground = Color(0xFFE8EAF0),

    surface = Color(0xFF14171E),
    onSurface = Color(0xFFE8EAF0),
    surfaceVariant = Color(0xFF1E222B),
    onSurfaceVariant = Color(0xFF9BA3B4),

    surfaceContainerLowest = Color(0xFF0B0D12),
    surfaceContainerLow = Color(0xFF12151B),
    surfaceContainer = Color(0xFF171A22),
    surfaceContainerHigh = Color(0xFF1E222B),
    surfaceContainerHighest = Color(0xFF252A34),

    outline = Color(0xFF3A414D),
    outlineVariant = Color(0xFF272C36),

    error = Color(0xFFE57367),
    onError = Color(0xFF3A0D08),
    errorContainer = Color(0xFF5C1710),
    onErrorContainer = Color(0xFFFBE4E1),

    scrim = Color(0xCC000000),
)

// ── 语义扩展色 ──────────────────────────────────────────────────────────────

/**
 * Material3 配色方案之外的项目专属语义色。
 * 通过 CompositionLocal 下发，避免在每个组件里硬编码颜色。
 */
@Immutable
data class KithSemanticColors(
    /** 关系图连线 */
    val edge: Color,
    val edgeStrong: Color,
    /** 中心「你」的节点 */
    val selfNode: Color,
    /** 关系强度渐变 */
    val relationLove: Color,
    val relationFamily: Color,
    val relationFriend: Color,
    val relationTension: Color,
    /** 状态栏毛玻璃底色 */
    val glassScrim: Color,
    val glassBorder: Color,
    /** 旁白气泡 */
    val narratorSurface: Color,
    val narratorAccent: Color,
    /** 聊天气泡 */
    val bubbleMe: Color,
    val onBubbleMe: Color,
    val bubbleThem: Color,
    val onBubbleThem: Color,
)

internal val LightSemantic = KithSemanticColors(
    edge = Color(0xFFD3D8E2),
    edgeStrong = Color(0xFFA9B1C0),
    selfNode = Amber,
    relationLove = Rose,
    relationFamily = Color(0xFFCE7B3A),
    relationFriend = Teal,
    relationTension = Color(0xFF8A8F9C),
    glassScrim = Color(0xE6F7F8FA),
    glassBorder = Color(0x14000000),
    narratorSurface = Color(0xFFF1F0FB),
    narratorAccent = Violet,
    bubbleMe = Ink,
    onBubbleMe = Color.White,
    bubbleThem = Color(0xFFF1F3F7),
    onBubbleThem = Color(0xFF14161C),
)

internal val DarkSemantic = KithSemanticColors(
    edge = Color(0xFF2C323D),
    edgeStrong = Color(0xFF454D5C),
    selfNode = Amber,
    relationLove = Color(0xFFE5839B),
    relationFamily = Color(0xFFE0A264),
    relationFriend = Color(0xFF4FBDB1),
    relationTension = Color(0xFF6B7280),
    glassScrim = Color(0xE60E1015),
    glassBorder = Color(0x1AFFFFFF),
    narratorSurface = Color(0xFF1D1A2E),
    narratorAccent = Color(0xFFA99BFF),
    bubbleMe = Amber,
    onBubbleMe = Color(0xFF221A08),
    bubbleThem = Color(0xFF232833),
    onBubbleThem = Color(0xFFE8EAF0),
)

val LocalKithColors = staticCompositionLocalOf { LightSemantic }

// ── 领域色 ──────────────────────────────────────────────────────────────────

/** 人物重要性 → 颜色。关系图上直接靠颜色区分层级。 */
fun Importance.color(): Color = when (this) {
    Importance.LEAD -> Amber
    Importance.MAJOR -> Violet
    Importance.SUPPORTING -> Color(0xFF4A86C8)
    Importance.MINOR -> Color(0xFF8A92A3)
    Importance.EXTRA -> Color(0xFFAEB4C0)
}

/** 日志类型 → 颜色。 */
fun LogKind.color(): Color = when (this) {
    LogKind.NARRATOR -> Violet
    LogKind.CHARACTER -> Teal
    LogKind.USER -> AmberDeep
    LogKind.VISION -> Color(0xFF4A86C8)
    LogKind.IMAGE -> Rose
    LogKind.MODEL -> Color(0xFF7A8291)
    LogKind.SYSTEM -> Color(0xFF8A92A3)
}

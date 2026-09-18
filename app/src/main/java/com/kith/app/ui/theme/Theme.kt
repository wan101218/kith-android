package com.kith.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

/**
 * 统一的圆角尺度。
 * 卡片用 18dp、气泡用 20dp、小控件用 12dp —— 偏圆的形态更贴近「人」而不像后台工具。
 */
val KithShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/**
 * Kith 主题。
 *
 * 刻意不启用 Material You 动态取色：品牌色（墨黑 + 琥珀）是所有界面里
 * 「你 / 当前 / 重要」三级信息的共同语义载体，被系统壁纸改掉就会失效。
 */
@Composable
fun KithTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) KithDarkScheme else KithLightScheme
    val semantic = if (darkTheme) DarkSemantic else LightSemantic

    CompositionLocalProvider(LocalKithColors provides semantic) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = KithTypography,
            shapes = KithShapes,
            content = content,
        )
    }
}

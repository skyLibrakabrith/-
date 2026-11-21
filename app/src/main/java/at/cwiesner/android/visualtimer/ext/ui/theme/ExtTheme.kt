package at.cwiesner.android.visualtimer.ext.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 纯黑深色主题。
 *
 * 关键点：背景与所有 surface 一律 `#000000`，不是常见的 `#121212`。
 * 在 OLED 屏上纯黑像素不发光，深夜里计时器界面不会整块泛灰。
 *
 * 层次靠**描边和半透明白**表达，而不是靠不同深度的灰底 ——
 * 这是纯黑主题唯一能维持层次的办法。
 */
object ExtColors {
    val PureBlack = Color(0xFF000000)

    /** 卡片/面板底色：比纯黑略亮一点点，用来在纯黑背景上勾出边界。 */
    val Surface = Color(0xFF0A0A0A)

    /** 描边色：纯黑主题里承担主要的分隔职责。 */
    val Outline = Color(0xFF2A2A2A)
    val OutlineStrong = Color(0xFF3D3D3D)

    /** 主色：Visual Timer 风格的高饱和蓝紫。 */
    val Primary = Color(0xFF7C9EFF)
    val PrimaryDim = Color(0xFF3A4F8A)

    /** 警示色阶：从充足到临近结束。 */
    val Amber = Color(0xFFFFC107)
    val Orange = Color(0xFFFF8A3D)
    val Red = Color(0xFFF44336)

    /** 文字。 */
    val TextPrimary = Color(0xFFF2F2F2)
    val TextSecondary = Color(0xFF8A8A8A)
    val TextDim = Color(0xFF5A5A5A)

    /** 波形/轨道底色。 */
    val Track = Color(0xFF1A1A1A)
}

private val PureBlackColors = darkColorScheme(
    primary = ExtColors.Primary,
    onPrimary = Color(0xFF001639),
    primaryContainer = ExtColors.PrimaryDim,
    onPrimaryContainer = Color(0xFFD8E2FF),
    secondary = Color(0xFF9FADC8),
    onSecondary = Color(0xFF0A1B33),
    tertiary = ExtColors.Amber,
    onTertiary = Color(0xFF2E1A00),
    error = ExtColors.Red,
    onError = Color(0xFF2A0000),
    background = ExtColors.PureBlack,
    onBackground = ExtColors.TextPrimary,
    surface = ExtColors.PureBlack,
    onSurface = ExtColors.TextPrimary,
    surfaceVariant = ExtColors.Surface,
    onSurfaceVariant = ExtColors.TextSecondary,
    outline = ExtColors.Outline,
    outlineVariant = ExtColors.Track,
    scrim = Color(0xCC000000),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3A5BC7),
    primaryContainer = Color(0xFFD8E2FF),
    onPrimaryContainer = Color(0xFF001639),
    tertiary = Color(0xFFB26A00),
    background = Color(0xFFFAFAFA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEFEFEF),
)

/**
 * @param forceDark 强制深色。默认 true —— 这个应用就是给夜间/专注场景用的，
 *   不跟随系统浅色，避免白天打开时突然一片白。
 */
@Composable
fun ExtTheme(
    forceDark: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = forceDark || isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) PureBlackColors else LightColors,
        content = content,
    )
}

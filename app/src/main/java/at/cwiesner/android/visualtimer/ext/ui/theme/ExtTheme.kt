package at.cwiesner.android.visualtimer.ext.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import at.cwiesner.android.visualtimer.ext.settings.ThemeMode

/**
 * 应用配色。
 *
 * 深色有两档：
 * - [PureBlack] 背景 #000000（OLED 不发光）
 * - [DarkGray]  背景 #121212（对比柔和，IPS 屏更耐看）
 *
 * 浅色一档，用于白天或强光环境。
 */
object ExtColors {
    // ---- 运行时可变的那一组：由 ExtTheme 按模式设置 ----
    var PureBlack: Color = Color(0xFF000000)
        internal set

    var Surface: Color = Color(0xFF0A0A0A)
        internal set

    var Outline: Color = Color(0xFF2A2A2A)
        internal set
    var OutlineStrong: Color = Color(0xFF3D3D3D)
        internal set

    var TextPrimary: Color = Color(0xFFF2F2F2)
        internal set
    var TextSecondary: Color = Color(0xFF8A8A8A)
        internal set
    var TextDim: Color = Color(0xFF5A5A5A)
        internal set

    var Track: Color = Color(0xFF1A1A1A)
        internal set

    // ---- 三档模式下都用同一套强调色 ----
    val Primary = Color(0xFF7C9EFF)
    val PrimaryDim = Color(0xFF3A4F8A)

    val Amber = Color(0xFFFFC107)
    val Orange = Color(0xFFFF8A3D)
    val Red = Color(0xFFF44336)

    /** 变色阈值使用的调色板，下标与 ColorThreshold.paletteIndex 对应。 */
    val Palette: List<Color> = listOf(Primary, Amber, Orange, Red)

    internal fun applyMode(mode: ThemeMode, systemDark: Boolean) {
        when (mode) {
            ThemeMode.PureBlack -> {
                PureBlack = Color(0xFF000000)
                Surface = Color(0xFF0A0A0A)
                Outline = Color(0xFF2A2A2A)
                OutlineStrong = Color(0xFF3D3D3D)
                TextPrimary = Color(0xFFF2F2F2)
                TextSecondary = Color(0xFF8A8A8A)
                TextDim = Color(0xFF5A5A5A)
                Track = Color(0xFF1A1A1A)
            }
            ThemeMode.DarkGray -> {
                PureBlack = Color(0xFF121212)
                Surface = Color(0xFF1E1E1E)
                Outline = Color(0xFF2C2C2C)
                OutlineStrong = Color(0xFF454545)
                TextPrimary = Color(0xFFEDEDED)
                TextSecondary = Color(0xFF9A9A9A)
                TextDim = Color(0xFF6A6A6A)
                Track = Color(0xFF262626)
            }
            ThemeMode.Light -> {
                PureBlack = Color(0xFFFAFAFA)
                Surface = Color(0xFFF0F0F2)
                Outline = Color(0xFFD6D6D8)
                OutlineStrong = Color(0xFFB0B0B4)
                TextPrimary = Color(0xFF141418)
                TextSecondary = Color(0xFF5A5A62)
                TextDim = Color(0xFF8A8A92)
                Track = Color(0xFFE2E2E6)
            }
            ThemeMode.System -> {
                if (systemDark) {
                    PureBlack = Color(0xFF000000)
                    Surface = Color(0xFF0A0A0A)
                    Outline = Color(0xFF2A2A2A)
                    OutlineStrong = Color(0xFF3D3D3D)
                    TextPrimary = Color(0xFFF2F2F2)
                    TextSecondary = Color(0xFF8A8A8A)
                    TextDim = Color(0xFF5A5A5A)
                    Track = Color(0xFF1A1A1A)
                } else {
                    PureBlack = Color(0xFFFAFAFA)
                    Surface = Color(0xFFF0F0F2)
                    Outline = Color(0xFFD6D6D8)
                    OutlineStrong = Color(0xFFB0B0B4)
                    TextPrimary = Color(0xFF141418)
                    TextSecondary = Color(0xFF5A5A62)
                    TextDim = Color(0xFF8A8A92)
                    Track = Color(0xFFE2E2E6)
                }
            }
        }
    }
}

private fun schemeFor(dark: Boolean) = if (dark) darkColorScheme(
    primary = ExtColors.Primary,
    onPrimary = Color(0xFF001639),
    primaryContainer = ExtColors.PrimaryDim,
    tertiary = ExtColors.Amber,
    error = ExtColors.Red,
    background = ExtColors.PureBlack,
    onBackground = ExtColors.TextPrimary,
    surface = ExtColors.PureBlack,
    onSurface = ExtColors.TextPrimary,
    surfaceVariant = ExtColors.Surface,
    onSurfaceVariant = ExtColors.TextSecondary,
    outline = ExtColors.Outline,
) else lightColorScheme(
    primary = Color(0xFF3A5BC7),
    onPrimary = Color.White,
    tertiary = Color(0xFFB26A00),
    background = ExtColors.PureBlack,
    onBackground = ExtColors.TextPrimary,
    surface = ExtColors.PureBlack,
    onSurface = ExtColors.TextPrimary,
    surfaceVariant = ExtColors.Surface,
    onSurfaceVariant = ExtColors.TextSecondary,
    outline = ExtColors.Outline,
)

@Composable
fun ExtTheme(
    mode: ThemeMode = ThemeMode.PureBlack,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    ExtColors.applyMode(mode, systemDark)
    val dark = mode != ThemeMode.Light && (mode != ThemeMode.System || systemDark)
    MaterialTheme(colorScheme = schemeFor(dark), content = content)
}

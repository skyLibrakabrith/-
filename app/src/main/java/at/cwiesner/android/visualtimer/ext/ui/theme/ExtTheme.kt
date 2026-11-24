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

    /**
     * 变色调色板（8 色，由冷到热）。
     *
     * 顺序即语义：越靠前越"充裕"，越靠后越"紧张"。
     * 阈值模式、等间隔模式、表盘比例模式都按这个顺序自动取色，
     * 用户不需要手动指定具体颜色，只需要决定"分几档"。
     */
    val Palette: List<Color> = listOf(
        Color(0xFF7C9EFF), // 0 蓝紫  — 充裕
        Color(0xFF56CCF2), // 1 天蓝
        Color(0xFF3FD0B0), // 2 青绿
        Color(0xFF9BD64B), // 3 草绿
        Color(0xFFFFC107), // 4 琥珀
        Color(0xFFFF9F1C), // 5 橙
        Color(0xFFFF6B35), // 6 橙红
        Color(0xFFF44336), // 7 红    — 紧张
    )

    /**
     * 红绿灯基准色：绿（充裕）→ 黄 → 橙 → 红（紧张）。
     *
     * 三档语义最直观，不需要用户理解刻度；中间用插值补出连续过渡，
     * 这样颜色是「慢慢变红」而不是「啪一下跳过去」。
     */
    private val GreenLight = Color(0xFF35D07F)
    private val LimeLight = Color(0xFF8FD14F)
    private val YellowLight = Color(0xFFF2C14E)
    private val OrangeLight = Color(0xFFF28F3B)
    private val RedLight = Color(0xFFE5484D)

    /**
     * 按剩余比例取「红绿灯」色。
     *
     * @param fraction 剩余比例 0..1（1 = 刚满，0 = 到点）
     * @param steps 中间档位数：0 = 纯三档跳变，1 = 多一档过渡，2 = 更平滑…
     *   实际取色位置是 `fraction` 在该档位上的量化值，档位越多过渡越细腻。
     */
    fun trafficColor(fraction: Float, steps: Int = 2): Color {
        val f = fraction.coerceIn(0f, 1f)
        // 把 1..0 映射到 0..1 的「紧张度」
        val tension = 1f - f
        // 四段等距：绿 → 黄绿 → 黄 → 橙 → 红
        // 之前只有绿/黄/橙/红三段插值，绿区占比过大，
        // 现在插入一个黄绿中间色，让四段均分 1/3，颜色推进更均匀
        val seg = tension * 4f
        val i = seg.toInt().coerceIn(0, 3)
        val t = (seg - i).coerceIn(0f, 1f)
        val from = when (i) {
            0 -> GreenLight
            1 -> LimeLight
            2 -> YellowLight
            else -> OrangeLight
        }
        val to = when (i) {
            0 -> LimeLight
            1 -> YellowLight
            2 -> OrangeLight
            else -> RedLight
        }
        return lerp(from, to, t)
    }

    private fun lerp(a: Color, b: Color, t: Float): Color {
        val k = t.coerceIn(0f, 1f)
        return Color(
            red = a.red + (b.red - a.red) * k,
            green = a.green + (b.green - a.green) * k,
            blue = a.blue + (b.blue - a.blue) * k,
            alpha = 1f,
        )
    }

    /** 调色板档位名称，用于设置页说明。 */
    val PaletteNames: List<String> = listOf(
        "蓝紫", "天蓝", "青绿", "草绿", "琥珀", "橙", "橙红", "红",
    )

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
    // 只在模式真正变化时改全局配色。
    // 之前每次重组都调用 applyMode + schemeFor，切换页面时会触发
    // 一轮不必要的整树重组，表现就是「打开新页面先闪一下」。
    androidx.compose.runtime.LaunchedEffect(mode, systemDark) {
        ExtColors.applyMode(mode, systemDark)
    }
    val dark = mode != ThemeMode.Light && (mode != ThemeMode.System || systemDark)
    val scheme = androidx.compose.runtime.remember(dark) { schemeFor(dark) }
    MaterialTheme(colorScheme = scheme, content = content)
}

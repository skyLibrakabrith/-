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
        Color(0xFF35D07F), // 0 绿    — 充裕（与红绿灯色带起点一致）
        Color(0xFF8FD14F), // 1 黄绿
        Color(0xFFF2C14E), // 2 黄
        Color(0xFFF28F3B), // 3 橙
        Color(0xFFE5484D), // 4 红    — 紧张
        Color(0xFF7C9EFF), // 5 蓝紫  — 中性/自定义
        Color(0xFF56CCF2), // 6 天蓝
        Color(0xFF9A6BFF), // 7 紫
    )

    /**
     * 红绿灯基准色：绿（充裕）→ 黄 → 红（紧张）。
     *
     * 三档语义最直观，不需要用户理解刻度；中间用插值补出连续过渡，
     * 这样颜色是「慢慢变红」而不是「啪一下跳过去」。
     *
     * 说明：早期版本这里是绿/黄绿/黄/橙/红五个锚点，绿区占比过大且
     * 后半段推进太慢。现在收成三个锚点、等距插值，「绿 → 黄 → 红」
     * 的推进在整个时间轴上均匀分布。
     */
    private val GreenLight = Color(0xFF35D07F)
    private val YellowLight = Color(0xFFF2C14E)
    private val RedLight = Color(0xFFE5484D)

    /**
     * 红绿灯色带的锚点：绿 → 黄 → 红。**起点必须是绿**。
     *
     * 之前用的是一张 8 色调色板（蓝紫→天蓝→青绿→草绿→琥珀→橙→橙红→红），
     * 剩余时间充裕时盘面是**蓝紫色** —— 用户明确要的是「从绿开始」，
     * 蓝紫在语义上既不是「充裕」也不是「危险」，看着更像停用状态。
     *
     * 现在三种模式（阈值 / 等间隔 / 表盘比例）都从这条色带上取色，
     * 中间靠插值填满，不会出现「跳色」。
     */
    private val TrafficAnchors: List<Color> = listOf(
        GreenLight, //  绿 — 充裕
        YellowLight, // 黄 — 过半
        RedLight, //    红 — 紧张
    )

    /** 色带首色（充裕端）。需要「从绿开始」的地方直接用它。 */
    val TrafficStart: Color get() = TrafficAnchors.first()

    /**
     * 按「充裕度」取红绿黄色。
     *
     * @param fraction 充裕度 1..0（1 = 满格 / 刚重置，0 = 到点）
     * @return 连续插值后的颜色，fraction=1 一定是绿，fraction=0 一定是红
     */
    fun trafficColor(fraction: Float): Color =
        gradientAt(TrafficAnchors, 1f - fraction.coerceIn(0f, 1f))

    /**
     * 把 [divisions] 份容器铺到红绿黄色带上，返回第 [index] 份的颜色。
     *
     * 用于「表盘比例」——表盘分成 N 份，每份一个颜色，
     * 第 0 份（剩余最多的那一份）取绿，最后一份取红。
     */
    fun trafficStepColor(index: Int, divisions: Int): Color {
        val n = divisions.coerceAtLeast(2)
        val i = index.coerceIn(0, n - 1)
        // 取每份「中间位置」的充裕度，避免首份直接贴边到纯绿、末份贴边到纯红
        val fraction = 1f - (i + 0.5f) / n
        return trafficColor(fraction)
    }

    /** 在 [anchors] 上按 0..1 位置取插值色。 */
    private fun gradientAt(anchors: List<Color>, position: Float): Color {
        if (anchors.size == 1) return anchors.first()
        val p = position.coerceIn(0f, 1f) * (anchors.size - 1)
        val i = p.toInt().coerceIn(0, anchors.size - 2)
        return lerp(anchors[i], anchors[i + 1], p - i)
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
        "绿", "黄绿", "黄", "橙", "红", "蓝紫", "天蓝", "紫",
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
    val dark = mode != ThemeMode.Light && (mode != ThemeMode.System || systemDark)

    // 配色必须在**绘制前同步就位**。
    // 之前放进 LaunchedEffect，首帧拿到的还是上一套颜色，
    // 下一帧才刷新 —— 这就是「打开页面先闪一下」的来源。
    // 这里用 remember 保证只在模式变化时重算 scheme，
    // applyMode 本身只是几个字段赋值，同步执行开销可忽略。
    val scheme = androidx.compose.runtime.remember(dark) {
        ExtColors.applyMode(mode, systemDark)
        schemeFor(dark)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

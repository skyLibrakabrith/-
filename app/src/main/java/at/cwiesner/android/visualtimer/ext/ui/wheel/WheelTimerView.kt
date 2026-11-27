package at.cwiesner.android.visualtimer.ext.ui.wheel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.cwiesner.android.visualtimer.ext.ui.ExtColors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 饼图式计时器盘面。
 *
 * 与「圆环」的区别：剩余量是一个**实心扇形**（`useCenter = true`），
 * 像切掉一块的饼，而不是描边圆环。这是 Visual Timer 的标志性形态。
 *
 * **内盘（多表盘设计）**：
 * 外盘表示总进度，在长时长下几乎看不出移动。因此中心再嵌一个小盘，
 * 以更细的时间粒度转动 —— 长时长时按「小时」走一圈，短时长时按「分钟」走。
 * 外盘看整体还剩多少，内盘看「现在确实在走」。
 *
 * @param innerDial true 时绘制内盘
 */
@Composable
fun PieTimerView(
    totalMillis: Long,
    remainingMillis: Long,
    running: Boolean,
    onDurationChange: ((Long) -> Unit)?,
    /** 拖动结束时提交一次 —— 持久化这类重活应该挂在这里，而不是每帧回调。 */
    onDurationCommit: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier,
    maxMinutes: Int = 60,
    /** 吸附步长（分钟）。支持 0.5 表示 30 秒一档。 */
    stepMinutes: Float = 1f,
    tickMinutes: Int = 5,
    centerLabel: String? = null,
    showCenterText: Boolean = true,
    innerDial: Boolean = true,
    /** 中央时间字号，网格里用小号。 */
    centerFontSize: Float = 40f,
    /** 变色阈值（剩余分钟数，降序）与对应颜色；为空时用内置四段色。 */
    thresholdMinutes: List<Long> = emptyList(),
    thresholdColors: List<Color> = emptyList(),
    /** 变色模式与对应参数。 */
    colorMode: at.cwiesner.android.visualtimer.ext.settings.ColorMode =
        at.cwiesner.android.visualtimer.ext.settings.ColorMode.Fraction,
    intervalMinutes: Long = 5L,
    fractionDivisions: Int = 4,
    /** 到点后的超时正计时；>0 时中央显示 +MM:SS 并转红色。 */
    overdueMillis: Long = 0L,
    /**
     * 保留签名以兼容调用方，行为已经统一。
     *
     * 早期这个参数决定「填满父容器」还是「强制正方形」，两种模式的几何基准
     * 不同，导致中央文字 / 内盘 / 圆按不同尺寸对齐，看起来就是错位的。
     * 现在盘面**恒为居中的最大内接正方形**，直径 = min(父宽, 父高)，
     * 这个参数不再有视觉效果。
     */
    @Suppress("UNUSED_PARAMETER")
    fillParent: Boolean = false,
    /**
     * 内盘是否可见。
     *
     * 拖动改时长时内盘会随每帧重组反复重绘，视觉上像在闪。
     * 由调用方在拖拽期间置为 false，松手再显示。
     */
    innerDialVisible: Boolean = true,
    /** 拖拽开始 / 结束时回调，供外部隐藏中央控件避免闪烁。 */
    onDragStateChange: ((Boolean) -> Unit)? = null,
) {
    val stepMillis = (stepMinutes * 60_000L).toLong()
    val maxMillis = maxMinutes * 60_000L
    val progress = if (totalMillis <= 0L) 0f
    else (remainingMillis.toFloat() / totalMillis).coerceIn(0f, 1f)

    var dragFraction by remember { mutableFloatStateOf(-1f) }

    val isOverdue = overdueMillis > 0L
    /**
     * 超时时的旋转指示角度（0..360，无限循环）。
     *
     * 为什么需要：到点后 `remainingMillis` 恒为 0，盘面停在"空"的状态一动不动 ——
     * 看起来像卡死了。超时是"还在走"的状态，必须让用户一眼看出它没停。
     * 所以画一段绕着圆环转的弧，转速 1.4 秒一圈（比 tick 快，但不刺眼）。
     *
     * 注意 transition 要**无条件创建**（Compose 规则：不能把它放在 if 里），
     * 只是在没超时时不参与绘制 —— 否则超时那一刻会重建动画、出现跳帧。
     */
    val spin = rememberInfiniteTransition(label = "overdue-spin")
    val spinAngle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
        ),
        label = "overdue-spin-angle",
    )
    val pieColor = if (isOverdue) ExtColors.Red else pickColor(
        remainingMillis = remainingMillis,
        totalMillis = totalMillis,
        running = running,
        mode = colorMode,
        thresholdMinutes = thresholdMinutes,
        thresholdColors = thresholdColors,
        intervalMinutes = intervalMinutes,
        fractionDivisions = fractionDivisions,
    )
    val trackColor = ExtColors.Track
    val tickColor = ExtColors.OutlineStrong
    val majorTickColor = ExtColors.TextDim
    // 暂停时内盘仍用运行时的同色系（只调淡），不再切到一个"编辑色"——
    // 换色会被误读成"切换了状态"，而不是"停了一下"。
    val innerColor = if (running) pieColor.copy(alpha = 0.55f) else ExtColors.PrimaryDim

    // 内盘粒度：长时长按小时，短时长按分钟
    val subUnit = if (totalMillis >= 3_600_000L) 3_600_000L else 60_000L
    val subProgress = if (totalMillis <= 0L) 0f
    else ((remainingMillis % subUnit).toFloat() / subUnit).coerceIn(0f, 1f)

    // 盘面始终是一个「居中的正方形」：
    // 直径取父容器宽高里较小的那个。之前 fillParent=true 时用 fillMaxSize，
    // Box 撑成竖长矩形，Canvas 内部按 minDimension 画圆 → 圆是正的，
    // 但中央文字、内盘控件和外面的标签各按不同基准对齐，看起来就是错位的。
    //
    // 用 BoxWithConstraints 而不是 Box：中央文字的字号要按表盘直径算，
    // 而 diameter 是 DrawScope 里的局部量，在 Canvas 外面拿不到
    // （上一版就是这么写崩的，CI 报 Unresolved reference 'diameter'）。
    // maxWidth 就是这块正方形区域的边长 = 表盘直径。
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .asSquareInside(),
        contentAlignment = Alignment.Center,
    ) {
        // 表盘直径（dp）。BoxWithConstraints 保证宽高相等，取 maxWidth 即可。
        val dialSizeDp: Dp = maxWidth

        Canvas(
            modifier = Modifier
                .aspectRatio(1f)
                .fillMaxSize()
                .pointerInput(running, onDurationChange, onDurationCommit, onDragStateChange, stepMillis, maxMillis) {
                    val editable = !running && onDurationChange != null
                    if (!editable) return@pointerInput
                    detectDragGestures(
                        onDragStart = { off ->
                            onDragStateChange?.invoke(true)
                            dragFraction = angleToFraction(off, size.width, size.height)
                            onDurationChange!!(
                                fractionToDuration(dragFraction, maxMillis, stepMillis),
                            )
                        },
                        onDragEnd = {
                            onDragStateChange?.invoke(false)
                            if (dragFraction >= 0f) {
                                onDurationCommit?.invoke(
                                    fractionToDuration(dragFraction, maxMillis, stepMillis),
                                )
                            }
                            dragFraction = -1f
                        },
                        onDragCancel = {
                            onDragStateChange?.invoke(false)
                            dragFraction = -1f
                        },
                        onDrag = { change, _ ->
                            dragFraction = angleToFraction(change.position, size.width, size.height)
                            onDurationChange!!(
                                fractionToDuration(dragFraction, maxMillis, stepMillis),
                            )
                        },
                    )
                }
                .pointerInput(running, onDurationChange, stepMillis, maxMillis) {
                    val editable = !running && onDurationChange != null
                    if (!editable) return@pointerInput
                    detectTapGestures { off ->
                        val f = angleToFraction(off, size.width, size.height)
                        onDurationChange!!(fractionToDuration(f, maxMillis, stepMillis))
                    }
                },
        ) {
            val diameter = size.minDimension
            val center = Offset(size.width / 2f, size.height / 2f)
            val pieRadius = diameter * 0.42f

            // ---- 1. 底盘（饼的"未切掉"部分） ----
            drawCircle(
                color = trackColor,
                radius = pieRadius,
                center = center,
            )

            // ---- 2. 实心扇形 ----
            //
            // 「暂停时外观不变样」是用户提过两次的要求（pickColor 里修过颜色那一半）。
            // 上一版只修了颜色，弧长还是照变：
            //   暂停时 shownFraction 走 else 分支，用的是「设定时长占整个刻度盘的比例」
            //   （totalMillis / maxMillis），而不是「剩余比例」——
            //   于是按下暂停，盘面会**猛地涨满**，看起来像被重置了。
            //
            // 现在：运行 / 暂停 / 超时 三种状态一律用同一个 progress；
            // dragFraction 只在「空闲且正在拖动改时长」时才生效（那时本就没有剩余比例可看）。
            val shownFraction = when {
                // 拖动改时长：只在没在跑的时候可能发生
                dragFraction >= 0f && !running -> dragFraction
                // 其余全部沿用剩余比例 —— 暂停时画面就此冻结，不跳变
                else -> progress
            }
            val sliceTopLeft = Offset(center.x - pieRadius, center.y - pieRadius)
            val sliceSize = Size(pieRadius * 2f, pieRadius * 2f)

            if (shownFraction > 0f) {
                drawArc(
                    // 暂停时只把同色系调淡一点（alpha 0.72），不换成 editColor。
                    // 换成另一个颜色会被误读成"变成了别的状态"，而不是"停了一下"。
                    color = if (running || isOverdue) pieColor else pieColor.copy(alpha = 0.72f),
                    startAngle = -90f,
                    sweepAngle = 360f * shownFraction,
                    useCenter = true,
                    topLeft = sliceTopLeft,
                    size = sliceSize,
                )
            }

            // ---- 2.5 超时：绕圆环转动的指示弧 ----
            //
            // 超时后 remaining 恒为 0，上面那段弧就不再变化、整个盘面一动不动。
            // 这里画一段短弧绕着圆环转，把"还在走"这个事实表达出来，
            // 否则用户看到的是一张静止的图，分不清是"超时中"还是"卡住了"。
            //
            // 画在饼**外沿**（半径略大于饼半径、用 Stroke 描边），
            // 不盖住已经填满的红色扇形，也不和中央的 +MM:SS 抢注意力。
            if (isOverdue) {
                val ringRadius = pieRadius * 1.04f
                val strokeWidth = (diameter * 0.035f).coerceAtLeast(2f)
                drawArc(
                    color = pieColor,
                    startAngle = spinAngle - 90f,
                    // 46° 的一段：太短看不出方向，太长像"又填了一块饼"
                    sweepAngle = 46f,
                    useCenter = false,
                    topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
                    size = Size(ringRadius * 2f, ringRadius * 2f),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            }

            // ---- 3. 外圈刻度（在饼外面一圈短刻度） ----
            val tickRadius = diameter * 0.485f
            val tickStep = if (maxMinutes / tickMinutes > 24) 5 else tickMinutes
            val tickCount = (maxMinutes / tickStep).coerceAtLeast(1)
            for (i in 0 until tickCount) {
                val angleDeg = 360f * i / tickCount - 90f
                val isMajor = i == 0 || (i * tickStep) % 15 == 0
                val len = if (isMajor) diameter * 0.030f else diameter * 0.014f
                val rad = Math.toRadians(angleDeg.toDouble())
                val outer = Offset(
                    center.x + (tickRadius * cos(rad)).toFloat(),
                    center.y + (tickRadius * sin(rad)).toFloat(),
                )
                val inner = Offset(
                    center.x + ((tickRadius - len) * cos(rad)).toFloat(),
                    center.y + ((tickRadius - len) * sin(rad)).toFloat(),
                )
                drawLine(
                    color = if (isMajor) majorTickColor else tickColor,
                    start = inner,
                    end = outer,
                    strokeWidth = if (isMajor) 2.5f else 1.5f,
                    cap = StrokeCap.Round,
                )
            }

            // ---- 4. 内盘（多表盘：显示更细粒度的时间流逝） ----
            if (innerDial && innerDialVisible) {
                val innerRadius = pieRadius * 0.46f
                drawCircle(
                    color = ExtColors.PureBlack,
                    radius = innerRadius,
                    center = center,
                )
                drawCircle(
                    color = trackColor,
                    radius = innerRadius,
                    center = center,
                    style = Stroke(width = diameter * 0.012f),
                )
                // 内盘同样是「暂停不变样」的一部分：
                // 原来未运行时会改成显示 (totalMillis % subUnit) / subUnit，
                // 一暂停内盘的弧长也会跟着跳一下。
                // 现在除了「空闲且正在拖动改时长」，其余情况一律沿用运行时的 subProgress，
                // 于是暂停时整个表盘（外圈饼 + 内盘）都冻结在同一帧。
                val subFrac = if (running || isOverdue) {
                    subProgress
                } else if (dragFraction >= 0f && onDurationChange != null) {
                    // 拖动改时长：内盘跟着动，作为"正在设定"的反馈
                    val shownTotal = (dragFraction * maxMillis).toLong()
                    if (subUnit > 0) (shownTotal % subUnit).toFloat() / subUnit else 0f
                } else {
                    subProgress
                }
                if (subFrac > 0f) {
                    drawArc(
                        color = innerColor,
                        startAngle = -90f,
                        sweepAngle = 360f * subFrac,
                        useCenter = true,
                        topLeft = Offset(center.x - innerRadius, center.y - innerRadius),
                        size = Size(innerRadius * 2f, innerRadius * 2f),
                    )
                }
                // 内盘刻度：4 段
                for (i in 0 until 4) {
                    val rad = Math.toRadians((90.0 * i - 90.0))
                    val o = Offset(
                        center.x + ((innerRadius - diameter * 0.012f) * cos(rad)).toFloat(),
                        center.y + ((innerRadius - diameter * 0.012f) * sin(rad)).toFloat(),
                    )
                    val i2 = Offset(
                        center.x + ((innerRadius - diameter * 0.045f) * cos(rad)).toFloat(),
                        center.y + ((innerRadius - diameter * 0.045f) * sin(rad)).toFloat(),
                    )
                    drawLine(
                        color = majorTickColor,
                        start = i2,
                        end = o,
                        strokeWidth = 1.5f,
                        cap = StrokeCap.Round,
                    )
                }
            }

            // ---- 5. 拖动手柄 ----
            if (!running && dragFraction >= 0f) {
                val endAngle = Math.toRadians(360.0 * dragFraction - 90.0)
                val knob = Offset(
                    center.x + (pieRadius * cos(endAngle)).toFloat(),
                    center.y + (pieRadius * sin(endAngle)).toFloat(),
                )
                drawCircle(color = Color.White, radius = diameter * 0.028f, center = knob)
            }
        }

        // ---- 6. 中央文字 ----
        if (showCenterText) {
            // 字号必须**跟着表盘直径走**，不能用固定的 centerFontSize。
            //
            // 原来写死 centerFontSize.sp：2 列时卡片宽、看着正好；切到 3/4 列后
            // 卡片和表盘一起变窄，字却不变，于是 "1:23:45" 这种较长的字符串
            // 直接溢出圆盘、被裁掉一半（用户反馈「多列时数字显示不全」）。
            //
            // 现在的算法：
            //   · 以表盘直径为基准取一个比例（直径的 13%），再夹到
            //     [centerFontSize 的 55%, centerFontSize] 区间里
            //   · 上限仍是调用方给的值，所以 2 列时观感不变
            //   · 下限 55% 保证再窄也读得清，不会缩成蚂蚁
            //   · 再用 widthIn 把文字宽度限制在圆盘内径以内
            //
            // 注意：这里**不能**用 Text 的 autoSize / TextAutoSize 参数 ——
            // 那是 compose-ui 1.8.0 才有的 API，本项目 BOM 2024.12.01 对应 1.7.5，
            // 用了会直接编译失败。所以靠「按直径算字号 + maxLines=1 + 限宽」来兜。
            val adaptiveFont = (dialSizeDp.value * 0.13f)
                .coerceIn(centerFontSize * 0.55f, centerFontSize)

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (isOverdue) "+" + formatBig(overdueMillis) else formatBig(remainingMillis),
                    color = when {
                        isOverdue -> Color.White
                        running -> Color.White
                        else -> ExtColors.TextPrimary
                    },
                    fontSize = adaptiveFont.sp,
                    fontWeight = FontWeight.Light,
                    // 单行且限宽：宁可小一点，也不能溢出圆盘被裁掉半截
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        // 内盘直径的 82%，留出圆周的呼吸空间
                        .widthIn(max = (dialSizeDp * 0.82f).coerceAtLeast(8.dp))
                        .padding(horizontal = 1.dp),
                )
                if (centerLabel != null) {
                    Text(
                        text = centerLabel,
                        color = if (running) Color.White.copy(alpha = 0.75f) else ExtColors.TextDim,
                        // 副标题同样跟着缩放，否则窄列下它比标题还抢地方
                        fontSize = (adaptiveFont * 0.38f).coerceIn(8f, 12f).sp,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/** 大号时间：`5:00`、`1:23:45`。 */
internal fun formatBig(millis: Long): String {
    if (millis <= 0L) return "0:00"
    val totalSec = (millis + 999) / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** 剩余比例 → 颜色（旧接口，保留给非阈值场景）。 */
internal fun ringColorFor(progress: Float, running: Boolean): Color = when {
    !running -> ExtColors.Primary
    progress > 0.5f -> ExtColors.Primary
    progress > 0.2f -> ExtColors.Amber
    progress > 0.05f -> ExtColors.Orange
    else -> ExtColors.Red
}

/**
 * 三种变色模式的统一入口。
 *
 * - **Threshold（阈值）**：剩余时间进入某个阈值就切成该档颜色
 * - **Interval（等间隔）**：每过 [intervalMinutes] 分钟换一种颜色，循环使用调色板
 * - **Fraction（表盘比例）**：把表盘按剩余比例分成 [fractionDivisions] 份，
 *   落在哪一份就用该份颜色 —— 视觉上像表盘被切成几块扇区各自上色
 *
 * 未运行时统一返回主色，避免静止状态下颜色乱跳。
 */
internal fun pickColor(
    remainingMillis: Long,
    totalMillis: Long,
    running: Boolean,
    mode: at.cwiesner.android.visualtimer.ext.settings.ColorMode,
    thresholdMinutes: List<Long>,
    thresholdColors: List<Color>,
    intervalMinutes: Long,
    fractionDivisions: Int,
): Color {
    val frac = if (totalMillis <= 0L) 0f
    else (remainingMillis.toFloat() / totalMillis).coerceIn(0f, 1f)

    // 暂停时**保持**暂停那一刻的颜色，不要跳变。
    //
    // 之前这里写的是「未运行就返回绿色」，结果一个跑到一半的橙色盘
    // 一按暂停直接跳成绿色，看起来像被重置了 ——
    // 用户明确要求「暂停的时候轮盘不要变化」。
    // 所以只有「完全没有进度」时才用起始绿，否则继续按剩余比例取色。
    if (!running && frac >= 0.999f) return ExtColors.TrafficStart

    return when (mode) {
        at.cwiesner.android.visualtimer.ext.settings.ColorMode.Threshold ->
            if (thresholdMinutes.isEmpty() || thresholdColors.isEmpty()) {
                // 没配阈值时用红绿灯连续色带：满格绿 → 到点红
                ExtColors.trafficColor(frac)
            } else {
                colorForRemaining(remainingMillis, running, thresholdMinutes, thresholdColors)
            }

        at.cwiesner.android.visualtimer.ext.settings.ColorMode.Interval -> {
            // 每 intervalMinutes 换一次色，但**从绿开始**：
            // 把整条时长切成等分，第 0 段（刚满）取绿，最后一段取红。
            val step = intervalMinutes.coerceAtLeast(1L) * 60_000L
            val buckets = (totalMillis / step).toInt().coerceAtLeast(1)
            // 剩余越少 → 落在越靠后的桶 → 越红
            val used = (((totalMillis - remainingMillis) / step).toInt())
                .coerceIn(0, buckets - 1)
            ExtColors.trafficStepColor(used, buckets)
        }

        at.cwiesner.android.visualtimer.ext.settings.ColorMode.Fraction -> {
            // 表盘按剩余比例分成 N 份，每份一个颜色，第 0 份取绿。
            //
            // 范围常量与 SettingsRepo / 设置页色带共用，三处必须一致：
            // 曾经一处写 2..8、另一处写 2..12，值在中间被静默夹过一次，
            // 表现出来就是「设置里 n 份、盘面按 n-1 份上色」。
            val divisions = fractionDivisions.coerceIn(
                at.cwiesner.android.visualtimer.ext.settings.SettingsRepo.MIN_FRACTION_DIVISIONS,
                at.cwiesner.android.visualtimer.ext.settings.SettingsRepo.MAX_FRACTION_DIVISIONS,
            )
            // 档位用**向下取整**（已走完几份），严格小于 divisions，不需要再夹上限。
            //
            // 关键：设置页的 PaletteStrip 第 i 格画的正是 trafficStepColor(i, divisions)，
            // 所以盘面此刻取的那个色一定能在色带上找到对应的一格 ——
            // 预览即所得，不会再出现「设置里看到一套色、盘面是另一套」。
            val usedStep = ((1f - frac) * divisions).toInt()
                .coerceIn(0, divisions - 1)
            ExtColors.trafficStepColor(usedStep, divisions)
        }
    }
}

/**
 * 按用户配置的「变色阈值」决定当前颜色。
 *
 * 阈值列表按剩余分钟数降序；剩余时间落进哪一档就用哪一档的颜色。
 * 剩余时间还大于第一档时，用第一档颜色。
 */
internal fun colorForRemaining(
    remainingMillis: Long,
    running: Boolean,
    thresholdMinutes: List<Long>,
    thresholdColors: List<Color>,
): Color {
    if (!running) return ExtColors.Primary
    if (thresholdMinutes.isEmpty() || thresholdColors.isEmpty()) {
        // 无配置时退回内置四段
        return when {
            remainingMillis > 60 * 60_000L -> ExtColors.Primary
            remainingMillis > 10 * 60_000L -> ExtColors.Amber
            remainingMillis > 3 * 60_000L -> ExtColors.Orange
            else -> ExtColors.Red
        }
    }
    val mins = remainingMillis / 60_000L
    for (i in thresholdMinutes.indices) {
        if (mins <= thresholdMinutes[i]) {
            return thresholdColors.getOrElse(i) { ExtColors.Primary }
        }
    }
    // 大于所有阈值 → 用第一档（最宽松）的颜色
    return thresholdColors.firstOrNull() ?: ExtColors.Primary
}

/** 手势位置 → 圈的比例（12 点为 0，顺时针 0..1）。 */
internal fun angleToFraction(position: Offset, width: Int, height: Int): Float {
    val cx = width / 2f
    val cy = height / 2f
    val dx = position.x - cx
    val dy = position.y - cy
    var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
    if (deg < 0f) deg += 360f
    if (deg >= 360f) deg -= 360f
    return deg / 360f
}

/** 圈比例 → 吸附后的时长。 */
internal fun fractionToDuration(fraction: Float, maxMillis: Long, stepMillis: Long): Long {
    if (maxMillis <= 0L || stepMillis <= 0L) return stepMillis
    val raw = (fraction * maxMillis).toLong()
    val snapped = Math.round(raw.toDouble() / stepMillis) * stepMillis
    return snapped.coerceIn(stepMillis, maxMillis)
}

/**
 * 把一个容器收成「父约束内的最大内接正方形」并居中。
 *
 * 卡片是竖长形（宽:高 ≈ 0.92），盘面上下的标签各占一行，
 * 中间这块区域往往横宽竖窄。只有显式收成正方形，
 * Canvas 画的圆、中央文字、内盘控件才会共用同一个几何中心。
 */
private fun Modifier.asSquareInside(): Modifier = this.layout { measurable, constraints ->
    val side = minOf(constraints.maxWidth, constraints.maxHeight).coerceAtLeast(1)
    val placeable = measurable.measure(Constraints.fixed(side, side))
    layout(side, side) {
        placeable.place((side - placeable.width) / 2, (side - placeable.height) / 2)
    }
}

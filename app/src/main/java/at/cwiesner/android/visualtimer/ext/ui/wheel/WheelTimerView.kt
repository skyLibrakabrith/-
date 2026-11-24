package at.cwiesner.android.visualtimer.ext.ui.wheel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
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
        at.cwiesner.android.visualtimer.ext.settings.ColorMode.Threshold,
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
    val editColor = ExtColors.Primary
    val innerColor = if (running) pieColor.copy(alpha = 0.55f) else ExtColors.PrimaryDim

    // 内盘粒度：长时长按小时，短时长按分钟
    val subUnit = if (totalMillis >= 3_600_000L) 3_600_000L else 60_000L
    val subProgress = if (totalMillis <= 0L) 0f
    else ((remainingMillis % subUnit).toFloat() / subUnit).coerceIn(0f, 1f)

    // 盘面始终是一个「居中的正方形」：
    // 直径取父容器宽高里较小的那个。之前 fillParent=true 时用 fillMaxSize，
    // Box 撑成竖长矩形，Canvas 内部按 minDimension 画圆 → 圆是正的，
    // 但中央文字、内盘控件和外面的标签各按不同基准对齐，看起来就是错位的。
    Box(
        modifier = modifier
            .fillMaxSize()
            .asSquareInside(),
        contentAlignment = Alignment.Center,
    ) {
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
            val shownFraction = when {
                running -> progress
                dragFraction >= 0f -> dragFraction
                else -> if (maxMillis > 0) (totalMillis.toFloat() / maxMillis).coerceIn(0f, 1f) else 0f
            }
            val sliceTopLeft = Offset(center.x - pieRadius, center.y - pieRadius)
            val sliceSize = Size(pieRadius * 2f, pieRadius * 2f)

            if (shownFraction > 0f) {
                drawArc(
                    color = if (running) pieColor else editColor,
                    startAngle = -90f,
                    sweepAngle = 360f * shownFraction,
                    useCenter = true,
                    topLeft = sliceTopLeft,
                    size = sliceSize,
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
                val subFrac = if (running) subProgress else {
                    // 未运行时内盘显示「当前设定的整点余数」，同样有视觉变化
                    if (totalMillis > 0L) (totalMillis % subUnit).toFloat() / subUnit else 0f
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
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (isOverdue) "+" + formatBig(overdueMillis) else formatBig(remainingMillis),
                    color = when {
                        isOverdue -> Color.White
                        running -> Color.White
                        else -> ExtColors.TextPrimary
                    },
                    fontSize = centerFontSize.sp,
                    fontWeight = FontWeight.Light,
                )
                if (centerLabel != null) {
                    Text(
                        text = centerLabel,
                        color = if (running) Color.White.copy(alpha = 0.75f) else ExtColors.TextDim,
                        fontSize = 12.sp,
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
            // 用「已走过第几份」而不是「剩余第几份」，
            // 这样位置和颜色在整条时间轴上是同一个方向。
            val divisions = fractionDivisions.coerceIn(2, 12)
            val usedStep = (((1f - frac) * divisions).toInt()).coerceIn(0, divisions - 1)
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

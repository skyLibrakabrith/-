package at.cwiesner.android.visualtimer.ext.ui.wheel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.font.FontWeight
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
    stepMinutes: Int = 1,
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
) {
    val stepMillis = stepMinutes * 60_000L
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

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(running, onDurationChange, onDurationCommit, stepMillis, maxMillis) {
                    val editable = !running && onDurationChange != null
                    if (!editable) return@pointerInput
                    detectDragGestures(
                        onDragStart = { off ->
                            dragFraction = angleToFraction(off, size.width, size.height)
                            onDurationChange!!(
                                fractionToDuration(dragFraction, maxMillis, stepMillis),
                            )
                        },
                        onDragEnd = {
                            if (dragFraction >= 0f) {
                                onDurationCommit?.invoke(
                                    fractionToDuration(dragFraction, maxMillis, stepMillis),
                                )
                            }
                            dragFraction = -1f
                        },
                        onDragCancel = { dragFraction = -1f },
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
            if (innerDial) {
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
    if (!running) return ExtColors.Primary
    val palette = if (thresholdColors.isNotEmpty()) thresholdColors else ExtColors.Palette

    return when (mode) {
        at.cwiesner.android.visualtimer.ext.settings.ColorMode.Threshold ->
            colorForRemaining(remainingMillis, running, thresholdMinutes, thresholdColors)

        at.cwiesner.android.visualtimer.ext.settings.ColorMode.Interval -> {
            val step = intervalMinutes.coerceAtLeast(1L) * 60_000L
            // 剩余的完整间隔数越多 → 越靠前的颜色；每少一个间隔换一次色
            val bucket = (remainingMillis / step).toInt()
            palette[Math.floorMod(bucket, palette.size)]
        }

        at.cwiesner.android.visualtimer.ext.settings.ColorMode.Fraction -> {
            val divisions = fractionDivisions.coerceIn(2, 12)
            val frac = if (totalMillis <= 0L) 0f
            else (remainingMillis.toFloat() / totalMillis).coerceIn(0f, 1f)
            // 剩余 100% → 第 0 份；剩余 0% → 最后一份
            val seg = ((1f - frac) * divisions).toInt().coerceIn(0, divisions - 1)
            palette[seg % palette.size]
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

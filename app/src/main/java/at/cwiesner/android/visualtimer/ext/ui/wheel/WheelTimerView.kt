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
    modifier: Modifier = Modifier,
    maxMinutes: Int = 60,
    stepMinutes: Int = 1,
    tickMinutes: Int = 5,
    centerLabel: String? = null,
    showCenterText: Boolean = true,
    innerDial: Boolean = true,
) {
    val stepMillis = stepMinutes * 60_000L
    val maxMillis = maxMinutes * 60_000L
    val progress = if (totalMillis <= 0L) 0f
    else (remainingMillis.toFloat() / totalMillis).coerceIn(0f, 1f)

    var dragFraction by remember { mutableFloatStateOf(-1f) }

    val pieColor = ringColorFor(progress, running)
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
                .pointerInput(running, onDurationChange, stepMillis, maxMillis) {
                    val editable = !running && onDurationChange != null
                    if (!editable) return@pointerInput
                    detectDragGestures(
                        onDragStart = { off ->
                            dragFraction = angleToFraction(off, size.width, size.height)
                            onDurationChange!!(
                                fractionToDuration(dragFraction, maxMillis, stepMillis),
                            )
                        },
                        onDragEnd = { dragFraction = -1f },
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
            val tickCount = (maxMinutes / tickMinutes).coerceAtLeast(1)
            for (i in 0 until tickCount) {
                val angleDeg = 360f * i / tickCount - 90f
                val isMajor = i == 0 || (i * tickMinutes) % 15 == 0
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
                    text = formatBig(remainingMillis),
                    color = if (running) Color.White else ExtColors.TextPrimary,
                    fontSize = 40.sp,
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

/** 剩余比例 → 颜色。 */
internal fun ringColorFor(progress: Float, running: Boolean): Color = when {
    !running -> ExtColors.Primary
    progress > 0.5f -> ExtColors.Primary
    progress > 0.2f -> ExtColors.Amber
    progress > 0.05f -> ExtColors.Orange
    else -> ExtColors.Red
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

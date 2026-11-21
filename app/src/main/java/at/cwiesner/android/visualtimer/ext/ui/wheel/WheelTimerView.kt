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
import androidx.compose.material3.MaterialTheme
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
import at.cwiesner.android.visualtimer.ext.domain.DeadlineCalculator
import at.cwiesner.android.visualtimer.ext.ui.ExtColors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 轮盘计时器 —— Visual Timer 风格的核心视觉。
 *
 * 交互：
 * - **设定状态**（[running] = false）：在圆盘上拖动，按时钟角度设定时长；
 *   从 12 点方向顺时针一圈 = [maxMinutes] 分钟。松手时吸附到 [stepMinutes] 的整数倍。
 * - **运行状态**（[running] = true）：圆盘变成进度指示，剩余部分从 12 点开始顺时针消退。
 *
 * 视觉层次（纯黑主题）：
 * 1. 外圈刻度环 —— 每 [tickMinutes] 分钟一个短刻度，每 [majorTickMinutes] 一个长刻度
 * 2. 进度环 —— 粗描边，颜色随剩余比例从主色 → 琥珀 → 橙 → 红
 * 3. 中央文字 —— 大号剩余时间，用小字号标注状态
 *
 * @param totalMillis 总时长
 * @param remainingMillis 剩余时长（设定状态下传 totalMillis 即可）
 * @param onDurationChange 拖动改变时长时回调（仅设定状态）
 */
@Composable
fun WheelTimerView(
    totalMillis: Long,
    remainingMillis: Long,
    running: Boolean,
    onDurationChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    maxMinutes: Int = 60,
    stepMinutes: Int = 1,
    tickMinutes: Int = 1,
    majorTickMinutes: Int = 5,
    showCenterText: Boolean = true,
    centerLabel: String? = null,
) {
    val stepMillis = stepMinutes * 60_000L
    val maxMillis = maxMinutes * 60_000L

    val progress = if (totalMillis <= 0L) 0f
    else (remainingMillis.toFloat() / totalMillis).coerceIn(0f, 1f)

    // 拖动中的原始角度（0..1 圈），用于画拖动指示
    var dragFraction by remember { mutableFloatStateOf(-1f) }

    val ringColor = ringColorFor(progress, running)
    val trackColor = ExtColors.Track
    val tickColor = ExtColors.OutlineStrong
    val majorTickColor = ExtColors.TextDim
    val dragColor = ExtColors.Primary

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(running, stepMillis, maxMillis) {
                    if (running) return@pointerInput
                    detectDragGestures(
                        onDragStart = { off ->
                            dragFraction = angleToFraction(off, size.width, size.height)
                            onDurationChange(fractionToDuration(dragFraction, maxMillis, stepMillis))
                        },
                        onDragEnd = { dragFraction = -1f },
                        onDragCancel = { dragFraction = -1f },
                        onDrag = { change, _ ->
                            dragFraction = angleToFraction(change.position, size.width, size.height)
                            onDurationChange(fractionToDuration(dragFraction, maxMillis, stepMillis))
                        },
                    )
                }
                .pointerInput(running, stepMillis, maxMillis) {
                    if (running) return@pointerInput
                    detectTapGestures { off ->
                        val f = angleToFraction(off, size.width, size.height)
                        onDurationChange(fractionToDuration(f, maxMillis, stepMillis))
                    }
                },
        ) {
            val diameter = size.minDimension
            val center = Offset(size.width / 2f, size.height / 2f)

            // ---- 1. 刻度环 ----
            val tickRingRadius = diameter * 0.455f
            val tickCount = maxMinutes / tickMinutes
            for (i in 0 until tickCount) {
                val angleDeg = 360f * i / tickCount - 90f
                val isMajor = (i * tickMinutes) % majorTickMinutes == 0
                val len = if (isMajor) diameter * 0.035f else diameter * 0.016f
                val rad = Math.toRadians(angleDeg.toDouble())
                val outer = Offset(
                    center.x + (tickRingRadius * cos(rad)).toFloat(),
                    center.y + (tickRingRadius * sin(rad)).toFloat(),
                )
                val inner = Offset(
                    center.x + ((tickRingRadius - len) * cos(rad)).toFloat(),
                    center.y + ((tickRingRadius - len) * sin(rad)).toFloat(),
                )
                drawLine(
                    color = if (isMajor) majorTickColor else tickColor,
                    start = inner,
                    end = outer,
                    strokeWidth = if (isMajor) 2.5f else 1.5f,
                    cap = StrokeCap.Round,
                )
            }

            // ---- 2. 轨道 ----
            val ringRadius = diameter * 0.375f
            val stroke = diameter * 0.075f
            val arcTopLeft = Offset(
                center.x - ringRadius - stroke / 2f,
                center.y - ringRadius - stroke / 2f,
            )
            val arcSize = Size(ringRadius * 2f + stroke, ringRadius * 2f + stroke)

            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )

            // ---- 3. 进度（或拖动指示） ----
            if (running) {
                if (progress > 0f) {
                    drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            } else {
                // 设定状态：显示即将设定的角度
                val shown = if (dragFraction >= 0f) dragFraction else {
                    if (maxMillis > 0) (totalMillis.toFloat() / maxMillis).coerceIn(0f, 1f) else 0f
                }
                if (shown > 0f) {
                    drawArc(
                        color = dragColor,
                        startAngle = -90f,
                        sweepAngle = 360f * shown,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    // 端点小球
                    val endAngle = Math.toRadians((360.0 * shown - 90.0))
                    val knobCenter = Offset(
                        center.x + (ringRadius * cos(endAngle)).toFloat(),
                        center.y + (ringRadius * sin(endAngle)).toFloat(),
                    )
                    drawCircle(
                        color = Color.Black,
                        radius = stroke * 0.42f,
                        center = knobCenter,
                    )
                    drawCircle(
                        color = dragColor,
                        radius = stroke * 0.30f,
                        center = knobCenter,
                    )
                }
            }
        }

        // ---- 4. 中央文字 ----
        if (showCenterText) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = formatBig(remainingMillis),
                    color = if (running) ringColor else ExtColors.TextPrimary,
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Light,
                )
                if (centerLabel != null) {
                    Text(
                        text = centerLabel,
                        color = ExtColors.TextDim,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                } else if (!running) {
                    Text(
                        text = "拖动圆盘设定时长",
                        color = ExtColors.TextDim,
                        fontSize = 13.sp,
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

/**
 * 把手势位置换算成「圈的比例」。
 * 12 点方向为 0，顺时针为正，返回值 0..1。
 */
internal fun angleToFraction(position: Offset, width: Int, height: Int): Float {
    val cx = width / 2f
    val cy = height / 2f
    val dx = position.x - cx
    val dy = position.y - cy
    // atan2(dy, dx) 给出相对 +x 轴的角度；+90 转到 12 点方向
    var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
    if (deg < 0f) deg += 360f
    if (deg >= 360f) deg -= 360f
    return deg / 360f
}

/** 圈的比例 → 吸附后的时长。 */
internal fun fractionToDuration(fraction: Float, maxMillis: Long, stepMillis: Long): Long {
    if (maxMillis <= 0L || stepMillis <= 0L) return stepMillis
    val raw = (fraction * maxMillis).toLong()
    val snapped = (raw.toDouble() / stepMillis).let { Math.round(it) } * stepMillis
    // 允许设到最大；最小不低于一个步长（拖一点点也至少 1 个单位）
    return snapped.coerceIn(stepMillis, maxMillis)
}

/** 供 UI 显示「这个圆盘一圈代表多少」。 */
internal fun describeWheel(maxMinutes: Int, stepMinutes: Int): String =
    "一圈 = ${DeadlineCalculator.formatRemaining(maxMinutes * 60_000L)} · 步进 $stepMinutes 分钟"

/** 位置到圆心的距离，用于判断是否在环上（暂未使用，留作扩展）。 */
internal fun isOnRing(position: Offset, width: Int, height: Int): Boolean {
    val cx = width / 2f
    val cy = height / 2f
    val d = Math.hypot((position.x - cx).toDouble(), (position.y - cy).toDouble()).toFloat()
    val radius = width / 2f
    return d > radius * 0.55f && abs(d - radius) < radius
}

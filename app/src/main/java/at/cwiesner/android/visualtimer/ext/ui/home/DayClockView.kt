package at.cwiesner.android.visualtimer.ext.ui.home

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
import androidx.compose.runtime.rememberUpdatedState
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
 * 24 小时大时钟。
 *
 * 一整圈 = 24 小时，0 点在上方，顺时针推进。
 * 从 0 点开始已经过的部分用实心扇形填充（像日晷），
 * 当前时刻用一根指针标出，外侧标注 0/6/12/18 四个主刻度。
 *
 * 拖动盘面 = 选择目标时刻；这是首页快速创建 DDL / 倒计时的入口。
 *
 * @param nowEpochMillis 当前时刻
 * @param onPick 用户拖动选择的目标时刻（epoch millis）
 * @param pickedEpochMillis 已选中的目标时刻；null 表示跟随「现在 + 默认偏移」
 */
@Composable
fun DayClockView(
    nowEpochMillis: Long,
    pickedEpochMillis: Long?,
    onPick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    showCenterText: Boolean = true,
) {
    var dragFraction by remember { mutableFloatStateOf(-1f) }
    // 关键：把 now 放进 rememberUpdatedState，pointerInput 的 key 用 Unit。
    // 之前 key 写的是 nowEpochMillis，now 每秒变一次 → 手势处理器每秒被取消重建，
    // 拖动几秒后就「卡住不动」了。
    val nowState = rememberUpdatedState(nowEpochMillis)

    val bgCircle = ExtColors.Surface
    val elapsedColor = ExtColors.PrimaryDim
    val handColor = ExtColors.Primary
    val pickColor = ExtColors.Amber
    val tickColor = ExtColors.OutlineStrong
    val majorTickColor = ExtColors.TextSecondary

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { off ->
                            dragFraction = fractionOfDay(off, size.width, size.height)
                            onPick(epochFromDayFraction(dragFraction, nowState.value))
                        },
                        onDragEnd = { dragFraction = -1f },
                        onDragCancel = { dragFraction = -1f },
                        onDrag = { change, _ ->
                            dragFraction = fractionOfDay(change.position, size.width, size.height)
                            onPick(epochFromDayFraction(dragFraction, nowState.value))
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures { off ->
                        val f = fractionOfDay(off, size.width, size.height)
                        onPick(epochFromDayFraction(f, nowState.value))
                    }
                },
        ) {
            val diameter = size.minDimension
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = diameter * 0.40f

            // 底盘
            drawCircle(color = bgCircle, radius = radius, center = center)

            // 今日已过部分
            val elapsed = fractionOfDay(nowEpochMillis)
            if (elapsed > 0f) {
                drawArc(
                    color = elapsedColor,
                    startAngle = -90f,
                    sweepAngle = 360f * elapsed,
                    useCenter = true,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2f, radius * 2f),
                )
            }

            // 24 小时刻度
            for (h in 0 until 24) {
                val angleDeg = 360f * h / 24f - 90f
                val isMajor = h % 6 == 0
                val len = if (isMajor) diameter * 0.045f else diameter * 0.022f
                val rad = Math.toRadians(angleDeg.toDouble())
                val outerR = radius * 1.10f
                val o = Offset(
                    center.x + (outerR * cos(rad)).toFloat(),
                    center.y + (outerR * sin(rad)).toFloat(),
                )
                val i = Offset(
                    center.x + ((outerR - len) * cos(rad)).toFloat(),
                    center.y + ((outerR - len) * sin(rad)).toFloat(),
                )
                drawLine(
                    color = if (isMajor) majorTickColor else tickColor,
                    start = i,
                    end = o,
                    strokeWidth = if (isMajor) 3f else 1.5f,
                    cap = StrokeCap.Round,
                )
            }

            // 当前时刻指针
            val nowAngle = Math.toRadians(elapsed * 360.0 - 90.0)
            drawLine(
                color = handColor,
                start = center,
                end = Offset(
                    center.x + (radius * 0.92f * cos(nowAngle)).toFloat(),
                    center.y + (radius * 0.92f * sin(nowAngle)).toFloat(),
                ),
                strokeWidth = diameter * 0.012f,
                cap = StrokeCap.Round,
            )
            drawCircle(color = handColor, radius = diameter * 0.018f, center = center)

            // 选中的目标时刻
            if (pickedEpochMillis != null) {
                val pf = fractionOfDay(pickedEpochMillis)
                val pa = Math.toRadians(pf * 360.0 - 90.0)
                val pk = Offset(
                    center.x + (radius * 0.80f * cos(pa)).toFloat(),
                    center.y + (radius * 0.80f * sin(pa)).toFloat(),
                )
                drawCircle(color = Color.Black, radius = diameter * 0.032f, center = pk)
                drawCircle(color = pickColor, radius = diameter * 0.022f, center = pk)
            }
        }

        if (showCenterText) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = clockText(nowEpochMillis),
                    color = ExtColors.TextPrimary,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Light,
                )
                Text(
                    text = "拖动盘面选择目标时刻",
                    color = ExtColors.TextDim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** 一天之内的比例（0 = 00:00，1 = 24:00）。 */
internal fun fractionOfDay(epochMillis: Long): Float {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
    val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val m = cal.get(java.util.Calendar.MINUTE)
    val s = cal.get(java.util.Calendar.SECOND)
    return (h * 3600f + m * 60f + s) / 86400f
}

internal fun fractionOfDay(position: Offset, width: Int, height: Int): Float {
    val cx = width / 2f
    val cy = height / 2f
    var deg = Math.toDegrees(
        atan2((position.y - cy).toDouble(), (position.x - cx).toDouble()),
    ).toFloat() + 90f
    if (deg < 0f) deg += 360f
    if (deg >= 360f) deg -= 360f
    return deg / 360f
}

/** 把「一天内的比例」换算成绝对时刻：以今天为基准，取最近的将来那个时刻。 */
internal fun epochFromDayFraction(fraction: Float, nowEpochMillis: Long): Long {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowEpochMillis }
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    val midnight = cal.timeInMillis
    val target = midnight + (fraction.toDouble() * 86_400_000L).toLong()
    // 已过则顺延到明天
    return if (target <= nowEpochMillis) target + 86_400_000L else target
}

internal fun clockText(epochMillis: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
    return "%02d:%02d".format(
        cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE),
    )
}

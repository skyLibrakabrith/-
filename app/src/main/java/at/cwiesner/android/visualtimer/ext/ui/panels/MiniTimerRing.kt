package at.cwiesner.android.visualtimer.ext.ui.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 面板里单个计时器的缩略圆环。
 *
 * 可视化要点：**弧长 = 剩余时间占比**。不用数字也能一眼看出快慢，
 * 并且用颜色区分阶段（充足 / 过半 / 临近结束）。
 */
@Composable
fun MiniTimerRing(
    label: String?,
    remainingMillis: Long,
    totalMillis: Long,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    running: Boolean = false,
) {
    val fraction = if (totalMillis <= 0L) 0f
    else (remainingMillis.toFloat() / totalMillis).coerceIn(0f, 1f)

    // Canvas 的绘制 lambda 是 DrawScope，不是 @Composable，颜色必须先在外部取出
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val ringColor = when {
        !running -> MaterialTheme.colorScheme.outlineVariant
        fraction > 0.5f -> MaterialTheme.colorScheme.primary
        fraction > 0.2f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(size)) {
                val stroke = this.size.minDimension * 0.12f
                val inset = stroke / 2f
                val arcSize = Size(this.size.width - stroke, this.size.height - stroke)

                // 轨道
                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                // 进度
                if (fraction > 0f) {
                    drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = 360f * fraction,
                        useCenter = false,
                        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Text(
                text = compactRemaining(remainingMillis),
                style = MaterialTheme.typography.labelMedium,
                color = if (running) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = label.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 极简剩余时间：`5` / `1:23` / `2h`。 */
internal fun compactRemaining(millis: Long): String {
    if (millis <= 0) return "0"
    val totalSec = millis / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "${h}h"
        m > 0 -> "%d:%02d".format(m, s)
        else -> "$s"
    }
}

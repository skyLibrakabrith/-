package at.cwiesner.android.visualtimer.ext.ui.duration

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import at.cwiesner.android.visualtimer.ext.domain.DeadlineCalculator

/**
 * DDL 倒推可视化。
 *
 * 画一条**时间轴**：左端是"现在"，右端是用户选的截止时刻，
 * 中间填充条长度 = 剩余时间占 24 小时的比例，
 * 并在轴上标出 24h 可靠阈值的位置。
 *
 * 这样用户选 DDL 时不用心算，直接看条就知道还剩多少、会不会超过精确闹钟上限。
 */
@Composable
fun DeadlineTimeline(
    remainingMillis: Long,
    modifier: Modifier = Modifier,
    state: DeadlineCalculator.Result = DeadlineCalculator.Result.Ok(
        remainingMillis = remainingMillis,
        requiresAlarmClock = remainingMillis > DeadlineCalculator.MAX_RELIABLE_MILLIS,
    ),
) {
    val isPast = state is DeadlineCalculator.Result.Past
    val isTooLong = state is DeadlineCalculator.Result.Ok && state.requiresAlarmClock

    // DrawScope 非 @Composable，颜色先取出
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val scaleColor = MaterialTheme.colorScheme.outline
    val fillColor = when {
        isPast -> MaterialTheme.colorScheme.error
        isTooLong -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    // 比例基准：24 小时；超过则画满
    val span = DeadlineCalculator.MAX_RELIABLE_MILLIS.toFloat()
    val ratio = (remainingMillis.toFloat() / span).coerceIn(0f, 1f)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp),
        ) {
            val barTop = size.height * 0.34f
            val barH = size.height * 0.30f

            // 轨道
            drawRect(
                color = trackColor,
                topLeft = Offset(0f, barTop),
                size = Size(size.width, barH),
            )
            // 剩余量
            if (!isPast && ratio > 0f) {
                drawRect(
                    color = fillColor,
                    topLeft = Offset(0f, barTop),
                    size = Size(size.width * ratio, barH),
                )
            }
            // 24h 阈值刻度（只在未超过时显示，因为超过时条已画满）
            if (!isTooLong && ratio < 1f) {
                val x = size.width
                drawLine(
                    color = scaleColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 2f,
                    cap = StrokeCap.Round,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    isPast -> "目标时刻已过"
                    else -> "剩余 " + DeadlineCalculator.formatRemaining(remainingMillis)
                },
                style = MaterialTheme.typography.labelMedium,
                color = fillColor,
            )
            Text(
                text = when {
                    isPast -> ""
                    isTooLong -> "> 24h，改用闹钟模式"
                    else -> "< 24h"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

package at.cwiesner.android.visualtimer.ext.ui.alerts

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 振动波形可视化。
 *
 * 把「关-开-关-开...」毫秒数组画成条状图：
 * - **实心块** = 振动段（奇偶交替中"开"的部分）
 * - **空白** = 间隔
 * - 块的**宽度严格按真实时间比例**，所以不同模式的疏密一眼可辨
 *
 * 这样用户不需要读「交响乐 / 心跳」这类文字名字，
 * 直接看图形就知道这段振动是什么节奏。
 */
@Composable
fun VibrationWaveform(
    pattern: List<Long>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    height: androidx.compose.ui.unit.Dp = 32.dp,
    showLabels: Boolean = false,
) {
    if (pattern.isEmpty()) {
        Text(
            text = "无振动",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }

    val total = pattern.sum().coerceAtLeast(1L)

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height),
        ) {
            // 背景轨道
            drawRect(
                color = trackColor,
                topLeft = Offset(0f, size.height * 0.38f),
                size = Size(size.width, size.height * 0.24f),
            )

            var cumulative = 0L
            pattern.forEachIndexed { index, segment ->
                val startFraction = cumulative.toFloat() / total
                val widthFraction = segment.toFloat() / total
                cumulative += segment

                // index 0 是延时（静默），奇数下标的段是"振动"
                if (index % 2 == 1) {
                    val left = size.width * startFraction
                    val w = (size.width * widthFraction).coerceAtLeast(1.5f)
                    drawRect(
                        color = barColor,
                        topLeft = Offset(left, size.height * 0.18f),
                        size = Size(w, size.height * 0.64f),
                    )
                }
            }
        }

        if (showLabels) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val segments = (pattern.size - 1) / 2
                Text(
                    text = "$segments 段脉冲",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatMillis(total),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 把毫秒格式化成 `1.4s` / `850ms`。 */
internal fun formatMillis(ms: Long): String =
    if (ms >= 1000) "%.1fs".format(ms / 1000.0) else "${ms}ms"

package at.cwiesner.android.visualtimer.ext.ui.duration

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * 时长选择器（图形化）。
 *
 * 拖一条对数刻度的滑块选时长：左端 10 秒，右端 6 小时。
 * 对数刻度让「30 秒」到「5 分钟」这段最常用的区间占据更多像素，
 * 比线性滑块的精度高得多。
 *
 * 下方把当前值同时显示为「时长」和「到点时刻」，
 * 让用户不用心算就能确认这个时长意味着几点结束。
 */
@Composable
fun DurationSlider(
    durationMillis: Long,
    onDurationChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    nowEpochMillis: Long = System.currentTimeMillis(),
) {
    val minMillis = 10_000L
    val maxMillis = 6L * 60 * 60 * 1000

    // 对数值 0..1
    val logMin = mathLog(minMillis.toFloat())
    val logMax = mathLog(maxMillis.toFloat())
    var fraction by remember {
        mutableFloatStateOf(
            ((mathLog(durationMillis.toFloat().coerceAtLeast(minMillis.toFloat())) - logMin) /
                (logMax - logMin)).coerceIn(0f, 1f),
        )
    }

    fun fractionToMillis(f: Float): Long {
        val v = mathExp(logMin + f * (logMax - logMin))
        // 吸附到「整」档位，避免出现 37 秒这种怪值
        return snapDuration(v.toLong()).coerceIn(minMillis, maxMillis)
    }

    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val activeColor = MaterialTheme.colorScheme.primary
    val knobColor = MaterialTheme.colorScheme.primary

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        val f = (change.position.x / size.width).coerceIn(0f, 1f)
                        fraction = f
                        onDurationChange(fractionToMillis(f))
                    }
                },
        ) {
            val cy = size.height / 2f
            val barH = size.height * 0.22f
            drawRect(
                color = trackColor,
                topLeft = Offset(0f, cy - barH / 2f),
                size = Size(size.width, barH),
            )
            drawRect(
                color = activeColor,
                topLeft = Offset(0f, cy - barH / 2f),
                size = Size(size.width * fraction, barH),
            )
            // 刻度：10s / 1m / 10m / 1h / 6h
            for (tick in listOf(10_000L, 60_000L, 600_000L, 3_600_000L, maxMillis)) {
                val tf = ((mathLog(tick.toFloat()) - logMin) / (logMax - logMin)).coerceIn(0f, 1f)
                drawLine(
                    color = trackColor,
                    start = Offset(size.width * tf, cy - barH),
                    end = Offset(size.width * tf, cy + barH),
                    strokeWidth = 2f,
                    cap = StrokeCap.Round,
                )
            }
            // 滑块
            drawCircle(
                color = knobColor,
                radius = size.height * 0.18f,
                center = Offset(size.width * fraction, cy),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = at.cwiesner.android.visualtimer.ext.domain.DeadlineCalculator
                    .formatRemaining(durationMillis),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "到 " + at.cwiesner.android.visualtimer.ext.domain.DeadlineCalculator
                    .describe(
                        java.time.Instant.ofEpochMilli(nowEpochMillis + durationMillis),
                        java.time.Instant.ofEpochMilli(nowEpochMillis),
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 把任意时长吸附到常用档位，减少无意义的精度。 */
internal fun snapDuration(millis: Long): Long {
    val candidates = listOf(
        10_000L, 15_000L, 30_000L, 45_000L,
        60_000L, 90_000L, 120_000L, 180_000L, 300_000L, 600_000L, 900_000L,
        1_200_000L, 1_800_000L, 2_700_000L, 3_600_000L,
        5_400_000L, 7_200_000L, 10_800_000L, 21_600_000L,
    )
    return candidates.minByOrNull { kotlin.math.abs(it - millis) } ?: millis
}

// Compose 里避免直接引 kotlin.math 的函数名冲突
private fun mathLog(v: Float): Float = kotlin.math.ln(v.toDouble()).toFloat()
private fun mathExp(v: Float): Float = kotlin.math.exp(v.toDouble()).toFloat()

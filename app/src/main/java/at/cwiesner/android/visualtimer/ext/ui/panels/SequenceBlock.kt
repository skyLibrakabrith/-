package at.cwiesner.android.visualtimer.ext.ui.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import at.cwiesner.android.visualtimer.ext.data.Step
import at.cwiesner.android.visualtimer.ext.ui.ExtColors
import kotlin.math.cos
import kotlin.math.sin

/**
 * 序列整块卡片 —— 占满网格一整行（`maxLineSpan`），盘面尺寸和首页的
 * 24 小时时钟同一量级。
 *
 * 视觉参考独孤的「圆形」计时器：一个**完整的圆形表盘**，
 * 每个阶段按自身时长占的**角度比例**切出一块楔形扇区，
 * 当前阶段整块高亮并叠加进度弧，走过的阶段变暗。
 *
 * 这样一眼能读出：
 * - 整套流程里有哪些阶段、各自多长（扇区宽窄）
 * - 现在走到第几个（哪块亮着）
 * - 当前阶段还剩多少（中央时间 + 进度弧）
 */
/** 圆形表盘本体：按阶段时长切扇区。 */
@Composable
fun SequenceDial(
    steps: List<Step>,
    currentIndex: Int,
    progress: Float,
    activeColor: Color,
    awaiting: Boolean,
    modifier: Modifier = Modifier,
) {
    val trackColor = ExtColors.Track
    val doneColor = ExtColors.TextDim.copy(alpha = 0.35f)
    val idleColor = ExtColors.PrimaryDim.copy(alpha = 0.55f)
    val dividerColor = ExtColors.OutlineStrong

    Canvas(modifier = modifier.fillMaxSize()) {
        val diameter = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = diameter * 0.44f
        val total = steps.sumOf { it.durationMillis }.coerceAtLeast(1L)

        if (steps.isEmpty()) {
            drawCircle(color = trackColor, radius = radius, center = center)
            return@Canvas
        }

        // ---- 各阶段楔形扇区 ----
        var ang = -90f
        steps.forEachIndexed { idx, step ->
            val sweep = 360f * (step.durationMillis.toFloat() / total)
            val color = when {
                idx < currentIndex -> doneColor
                idx == currentIndex -> activeColor
                else -> idleColor
            }
            drawArc(
                color = color,
                startAngle = ang,
                sweepAngle = (sweep - 2.5f).coerceAtLeast(1f),
                useCenter = true,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2f, radius * 2f),
            )
            ang += sweep
        }

        // ---- 当前阶段进度环 ----
        if (currentIndex >= 0 && currentIndex < steps.size && progress > 0f) {
            var segStart = -90f
            for (i in 0 until currentIndex) {
                segStart += 360f * (steps[i].durationMillis.toFloat() / total)
            }
            val segSweep = 360f * (steps[currentIndex].durationMillis.toFloat() / total)
            val ringR = radius * 1.06f
            val stroke = diameter * 0.035f
            drawArc(
                color = Color.White,
                startAngle = segStart,
                sweepAngle = segSweep * progress,
                useCenter = false,
                topLeft = Offset(center.x - ringR, center.y - ringR),
                size = Size(ringR * 2f, ringR * 2f),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }

        // ---- 阶段分隔刻度 ----
        var tick = -90f
        steps.forEach { step ->
            val a = Math.toRadians(tick.toDouble())
            drawLine(
                color = dividerColor,
                start = Offset(
                    center.x + (radius * 0.55f * cos(a)).toFloat(),
                    center.y + (radius * 0.55f * sin(a)).toFloat(),
                ),
                end = Offset(
                    center.x + (radius * cos(a)).toFloat(),
                    center.y + (radius * sin(a)).toFloat(),
                ),
                strokeWidth = 1.5f,
            )
            tick += 360f * (step.durationMillis.toFloat() / total)
        }

        // 中心挖空，给文字留位置
        drawCircle(color = ExtColors.PureBlack, radius = radius * 0.55f, center = center)
        // 超时状态：外圈再套一圈红
        if (awaiting) {
            drawCircle(
                color = activeColor.copy(alpha = 0.5f),
                radius = radius * 0.55f,
                center = center,
                style = Stroke(width = 2f),
            )
        }
    }
}


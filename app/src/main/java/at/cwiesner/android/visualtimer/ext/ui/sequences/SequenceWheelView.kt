package at.cwiesner.android.visualtimer.ext.ui.sequences

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.cwiesner.android.visualtimer.ext.data.Step
import at.cwiesner.android.visualtimer.ext.domain.DeadlineCalculator
import at.cwiesner.android.visualtimer.ext.domain.StepSequenceEngine
import at.cwiesner.android.visualtimer.ext.ui.ExtColors
import kotlin.math.cos
import kotlin.math.sin

/**
 * 序列轮盘。
 *
 * 与首页 24 小时时钟同构：**一个盘面承载全部阶段**。
 * 每个阶段按自身时长占的**角度比例**分到一段扇区，
 * 这样一眼就能看出整套流程里哪个阶段最重、现在走到哪儿。
 *
 * 视觉分层：
 * - 底环：全部阶段的静态分区（不同深浅交替，便于分辨边界）
 * - 前景扇区：当前阶段已走过的部分，颜色随剩余比例变化
 * - 中央：当前阶段的剩余时间 + 阶段序号
 */
@Composable
fun SequenceWheelView(
    steps: List<Step>,
    phase: StepSequenceEngine.Phase,
    modifier: Modifier = Modifier,
) {
    if (steps.isEmpty()) {
        Box(modifier = modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
            Text("还没有阶段", color = ExtColors.TextDim, fontSize = 13.sp)
        }
        return
    }

    val totalMillis = steps.sumOf { it.durationMillis }.coerceAtLeast(1L)

    // 当前阶段与进度
    val currentIndex = when (phase) {
        is StepSequenceEngine.Phase.Running -> phase.stepIndex
        is StepSequenceEngine.Phase.AwaitingConfirm -> phase.finishedStepIndex
        else -> -1
    }
    val currentProgress = when (phase) {
        is StepSequenceEngine.Phase.Running -> phase.progress
        is StepSequenceEngine.Phase.AwaitingConfirm -> 0f
        else -> 0f
    }
    val remainText = when (phase) {
        is StepSequenceEngine.Phase.Running -> DeadlineCalculator.formatRemaining(phase.remainingMillis)
        is StepSequenceEngine.Phase.AwaitingConfirm ->
            "+" + DeadlineCalculator.formatRemaining(phase.elapsedMillis)
        is StepSequenceEngine.Phase.Finished -> "完成"
        else -> "待开始"
    }

    val trackColor = ExtColors.Track
    val dividerColor = ExtColors.OutlineStrong
    val dimColor = ExtColors.PrimaryDim
    val activeColor = ExtColors.Primary
    val doneColor = ExtColors.TextDim

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val diameter = size.minDimension
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = diameter * 0.40f
            val stroke = diameter * 0.10f

            // ---- 1. 底环 + 阶段分区 ----
            var startAngle = -90f
            steps.forEachIndexed { index, step ->
                val sweep = 360f * (step.durationMillis.toFloat() / totalMillis)
                val color = when {
                    index < currentIndex -> doneColor.copy(alpha = 0.35f)
                    index == currentIndex -> dimColor
                    else -> trackColor
                }
                drawArc(
                    color = color,
                    startAngle = startAngle,
                    sweepAngle = sweep - 1.5f,   // 留一点缝，便于分辨边界
                    useCenter = false,
                    topLeft = Offset(
                        center.x - radius - stroke / 2f,
                        center.y - radius - stroke / 2f,
                    ),
                    size = Size(radius * 2f + stroke, radius * 2f + stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Butt),
                )
                startAngle += sweep
            }

            // ---- 2. 当前阶段前景弧 ----
            if (currentIndex >= 0 && currentIndex < steps.size) {
                var segStart = -90f
                for (i in 0 until currentIndex) {
                    segStart += 360f * (steps[i].durationMillis.toFloat() / totalMillis)
                }
                val segSweep = 360f * (steps[currentIndex].durationMillis.toFloat() / totalMillis)
                if (currentProgress > 0f) {
                    drawArc(
                        color = activeColor,
                        startAngle = segStart,
                        sweepAngle = segSweep * currentProgress,
                        useCenter = false,
                        topLeft = Offset(
                            center.x - radius - stroke / 2f,
                            center.y - radius - stroke / 2f,
                        ),
                        size = Size(radius * 2f + stroke, radius * 2f + stroke),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }

                // 当前阶段的起始刻度，像一个指针
                val rad = Math.toRadians((segStart + 90.0))
                val outerR = radius + stroke
                drawLine(
                    color = activeColor,
                    start = Offset(
                        center.x + ((outerR - stroke) * cos(Math.toRadians(segStart.toDouble()))).toFloat(),
                        center.y + ((outerR - stroke) * sin(Math.toRadians(segStart.toDouble()))).toFloat(),
                    ),
                    end = Offset(
                        center.x + (outerR * cos(Math.toRadians(segStart.toDouble()))).toFloat(),
                        center.y + (outerR * sin(Math.toRadians(segStart.toDouble()))).toFloat(),
                    ),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round,
                )
            }

            // ---- 3. 阶段分隔刻度 ----
            var tickAngle = -90f
            steps.forEach { step ->
                val a = Math.toRadians(tickAngle.toDouble())
                drawLine(
                    color = dividerColor,
                    start = Offset(
                        center.x + ((radius - stroke / 2f) * cos(a)).toFloat(),
                        center.y + ((radius - stroke / 2f) * sin(a)).toFloat(),
                    ),
                    end = Offset(
                        center.x + ((radius + stroke / 2f) * cos(a)).toFloat(),
                        center.y + ((radius + stroke / 2f) * sin(a)).toFloat(),
                    ),
                    strokeWidth = 1.5f,
                )
                tickAngle += 360f * (step.durationMillis.toFloat() / totalMillis)
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (currentIndex >= 0) {
                Text(
                    "第 ${currentIndex + 1}/${steps.size} 阶段",
                    color = ExtColors.TextDim,
                    fontSize = 11.sp,
                )
            }
            Text(
                text = remainText,
                color = ExtColors.TextPrimary,
                fontSize = 34.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(top = 2.dp),
            )
            steps.getOrNull(currentIndex)?.label?.let {
                Text(
                    it,
                    color = ExtColors.TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

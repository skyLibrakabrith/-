package at.cwiesner.android.visualtimer.ext.ui.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.cwiesner.android.visualtimer.ext.data.Step
import at.cwiesner.android.visualtimer.ext.data.StepSequence
import at.cwiesner.android.visualtimer.ext.domain.DeadlineCalculator
import at.cwiesner.android.visualtimer.ext.domain.StepSequenceEngine
import at.cwiesner.android.visualtimer.ext.ui.ExtColors

/**
 * 面板里的序列卡片。
 *
 * 与计时器/组**同尺寸**占一个格子。盘面用「分段环」表达：
 * 每个阶段按时长占比分一段，当前阶段高亮 —— 等比缩小版的序列轮盘。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SequenceCell(
    sequence: StepSequence,
    steps: List<Step>,
    phase: StepSequenceEngine.Phase,
    isActive: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentIndex = when (phase) {
        is StepSequenceEngine.Phase.Running -> phase.stepIndex
        is StepSequenceEngine.Phase.AwaitingConfirm -> phase.finishedStepIndex
        else -> -1
    }
    val progress = when (phase) {
        is StepSequenceEngine.Phase.Running -> phase.progress
        else -> 0f
    }
    val centerText = when (phase) {
        is StepSequenceEngine.Phase.Running ->
            DeadlineCalculator.formatRemaining(phase.remainingMillis)
        is StepSequenceEngine.Phase.AwaitingConfirm ->
            "+" + DeadlineCalculator.formatRemaining(phase.elapsedMillis)
        else -> DeadlineCalculator.formatRemaining(steps.sumOf { it.durationMillis })
    }

    val activeColor = if (phase is StepSequenceEngine.Phase.AwaitingConfirm) {
        ExtColors.Red
    } else {
        ExtColors.Primary
    }
    val trackColor = ExtColors.Track
    val doneColor = ExtColors.TextDim.copy(alpha = 0.4f)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.92f)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(16.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val diameter = size.minDimension
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val radius = diameter * 0.38f
                        val stroke = diameter * 0.12f
                        val total = steps.sumOf { it.durationMillis }.coerceAtLeast(1L)

                        if (steps.isEmpty()) {
                            drawArc(
                                color = trackColor,
                                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                                topLeft = Offset(
                                    center.x - radius - stroke / 2f,
                                    center.y - radius - stroke / 2f,
                                ),
                                size = Size(radius * 2f + stroke, radius * 2f + stroke),
                                style = Stroke(width = stroke, cap = StrokeCap.Butt),
                            )
                        } else {
                            var ang = -90f
                            steps.forEachIndexed { idx, step ->
                                val sweep = 360f * (step.durationMillis.toFloat() / total)
                                drawArc(
                                    color = when {
                                        idx < currentIndex -> doneColor
                                        idx == currentIndex -> activeColor.copy(alpha = 0.45f)
                                        else -> trackColor
                                    },
                                    startAngle = ang,
                                    sweepAngle = (sweep - 2f).coerceAtLeast(1f),
                                    useCenter = false,
                                    topLeft = Offset(
                                        center.x - radius - stroke / 2f,
                                        center.y - radius - stroke / 2f,
                                    ),
                                    size = Size(radius * 2f + stroke, radius * 2f + stroke),
                                    style = Stroke(width = stroke, cap = StrokeCap.Butt),
                                )
                                ang += sweep
                            }
                            // 当前阶段进度覆盖
                            if (currentIndex >= 0 && progress > 0f) {
                                var segStart = -90f
                                for (i in 0 until currentIndex) {
                                    segStart += 360f * (steps[i].durationMillis.toFloat() / total)
                                }
                                val segSweep =
                                    360f * (steps[currentIndex].durationMillis.toFloat() / total)
                                drawArc(
                                    color = activeColor,
                                    startAngle = segStart,
                                    sweepAngle = segSweep * progress,
                                    useCenter = false,
                                    topLeft = Offset(
                                        center.x - radius - stroke / 2f,
                                        center.y - radius - stroke / 2f,
                                    ),
                                    size = Size(radius * 2f + stroke, radius * 2f + stroke),
                                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                                )
                            }
                        }
                    }
                    // 中央时间
                    Text(
                        text = centerText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Light,
                        color = ExtColors.TextPrimary,
                    )
                }

                Text(
                    text = sequence.name,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = ExtColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = if (isActive) {
                        "第 ${currentIndex + 1}/${steps.size} 阶段"
                    } else {
                        "${steps.size} 阶段"
                    },
                    fontSize = 9.sp,
                    color = if (isActive) activeColor else ExtColors.TextDim,
                    maxLines = 1,
                )
            }

            // 序列标记点：右下角，用青色区分于组的琥珀色
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(ExtColors.Palette[2]),
            )
        }
    }
}

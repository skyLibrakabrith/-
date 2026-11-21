package at.cwiesner.android.visualtimer.ext.ui.sequences

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import at.cwiesner.android.visualtimer.ext.domain.StepSequenceEngine
import at.cwiesner.android.visualtimer.ext.domain.DeadlineCalculator

/**
 * 逐步间隔的运行界面。
 *
 * 三块内容，全部图形化：
 * 1. **顶部步骤条**：每个 step 一小段，走过的变实心，当前段在脉动
 * 2. **中央大圆环**：当前步剩余比例
 * 3. **确认区**：等待确认时显示超时环形倒计时 + 「下一阶段」按钮
 */
@Composable
fun SequenceRunnerScreen(
    phase: StepSequenceEngine.Phase,
    stepCount: Int,
    onConfirm: () -> Unit,
    onSkip: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            StepProgressBar(
                stepCount = stepCount,
                currentIndex = when (phase) {
                    is StepSequenceEngine.Phase.Running -> phase.stepIndex
                    is StepSequenceEngine.Phase.AwaitingConfirm -> phase.finishedStepIndex
                    else -> -1
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                when (phase) {
                    is StepSequenceEngine.Phase.Idle ->
                        Text("未开始", style = MaterialTheme.typography.headlineSmall)

                    is StepSequenceEngine.Phase.Finished ->
                        Text("已完成", style = MaterialTheme.typography.headlineMedium)

                    is StepSequenceEngine.Phase.Running -> RunningBody(phase)

                    is StepSequenceEngine.Phase.AwaitingConfirm -> AwaitingBody(
                        phase = phase,
                        onConfirm = onConfirm,
                        onSkip = onSkip,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextButton(onClick = onStop) { Text("停止") }
            }
        }
    }
}

@Composable
private fun RunningBody(phase: StepSequenceEngine.Phase.Running) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = phase.step.label ?: "第 ${phase.stepIndex + 1} 阶段",
            style = MaterialTheme.typography.titleMedium,
        )
        BigRing(progress = phase.progress)
        Text(
            text = DeadlineCalculator.formatRemaining(phase.remainingMillis),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Light,
        )
    }
}

@Composable
private fun AwaitingBody(
    phase: StepSequenceEngine.Phase.AwaitingConfirm,
    onConfirm: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = phase.finishedStep.label ?: "第 ${phase.finishedStepIndex + 1} 阶段完成",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )

        // 超时环形 → 到点后转正计时（显示已超出多久）
        val overdue = phase.overdue
        val timerText = if (overdue) {
            "+" + DeadlineCalculator.formatRemaining(phase.overtimeMillis)
        } else {
            "${phase.timeoutRemainingMillis / 1000}s"
        }

        Box(contentAlignment = Alignment.Center) {
            TimeoutRing(overdue = overdue)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = timerText,
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (overdue) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                )
                if (overdue) {
                    Text(
                        text = "已超时",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Text(
            text = phase.nextStep?.let {
                "下一阶段：${it.label ?: DeadlineCalculator.formatRemaining(it.durationMillis)}"
            } ?: "这是最后一步",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 关键：超时不自动前进，必须点这里
        Button(onClick = onConfirm) { Text("下一阶段") }
        TextButton(onClick = onSkip) { Text("跳过") }
    }
}

/** 顶部步骤条：已过的段变实心，当前段加粗。 */
@Composable
private fun StepProgressBar(
    stepCount: Int,
    currentIndex: Int,
    modifier: Modifier = Modifier,
) {
    if (stepCount <= 0) return
    // DrawScope 非 @Composable，颜色必须在进入绘制 lambda 之前取出
    val doneColor = MaterialTheme.colorScheme.primary
    val pendingColor = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier = modifier.height(10.dp)) {
        val gap = 4f
        val segW = (size.width - gap * (stepCount - 1)) / stepCount
        repeat(stepCount) { i ->
            val color = if (i <= currentIndex) doneColor else pendingColor
            drawRect(
                color = color,
                topLeft = Offset(i * (segW + gap), 0f),
                size = Size(segW, size.height),
            )
        }
    }
}

/** 大圆环，只显示进度。 */
@Composable
private fun BigRing(progress: Float, size: androidx.compose.ui.unit.Dp = 200.dp) {
    Canvas(modifier = Modifier.height(size)) {
        val stroke = this.size.minDimension * 0.07f
        val inset = stroke / 2f
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
        drawArc(
            color = Color.LightGray.copy(alpha = 0.3f),
            startAngle = -90f, sweepAngle = 360f, useCenter = false,
            topLeft = Offset(inset, inset), size = arcSize,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
        )
        drawArc(
            color = Color(0xFF4A90D9),
            startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
            topLeft = Offset(inset, inset), size = arcSize,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
        )
    }
}

/** 超时倒计时环。 */
@Composable
private fun TimeoutRing(overdue: Boolean) {
    Canvas(modifier = Modifier.height(96.dp)) {
        val stroke = 8f
        val inset = stroke / 2f
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
        drawArc(
            color = if (overdue) Color(0xFFE53935) else Color(0xFFFFA726),
            startAngle = -90f,
            sweepAngle = if (overdue) 360f else 270f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
        )
    }
}

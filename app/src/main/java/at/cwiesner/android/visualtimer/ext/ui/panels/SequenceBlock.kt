package at.cwiesner.android.visualtimer.ext.ui.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.Color
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
@Composable
fun SequenceBlock(
    sequence: StepSequence,
    steps: List<Step>,
    phase: StepSequenceEngine.Phase,
    isActive: Boolean,
    onStart: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
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
    val awaiting = phase is StepSequenceEngine.Phase.AwaitingConfirm
    val centerText = when (phase) {
        is StepSequenceEngine.Phase.Running ->
            DeadlineCalculator.formatRemaining(phase.remainingMillis)
        is StepSequenceEngine.Phase.AwaitingConfirm ->
            "+" + DeadlineCalculator.formatRemaining(phase.elapsedMillis)
        is StepSequenceEngine.Phase.Finished -> "完成"
        else -> DeadlineCalculator.formatRemaining(steps.sumOf { it.durationMillis })
    }
    val activeColor = if (awaiting) ExtColors.Red else ExtColors.Primary

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onLongClick)
            .clip(RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = sequence.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = ExtColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (isActive && currentIndex >= 0) {
                        "第 ${currentIndex + 1}/${steps.size} 阶段"
                    } else {
                        "${steps.size} 阶段"
                    },
                    fontSize = 11.sp,
                    color = if (isActive) activeColor else ExtColors.TextDim,
                )
            }

            // 大圆盘
            val landscape = at.cwiesner.android.visualtimer.ext.ui.isLandscape()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(
                        min = if (landscape) 150.dp else 200.dp,
                        max = if (landscape) 190.dp else 260.dp,
                    )
                    .padding(vertical = if (landscape) 4.dp else 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                SequenceDial(
                    steps = steps,
                    currentIndex = currentIndex,
                    progress = progress,
                    activeColor = activeColor,
                    awaiting = awaiting,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = centerText,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Light,
                        color = ExtColors.TextPrimary,
                    )
                    steps.getOrNull(currentIndex)?.label?.let {
                        Text(
                            it,
                            fontSize = 12.sp,
                            color = ExtColors.TextSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }

            // 操作行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!isActive) {
                    BlockButton("开始", true, Modifier.weight(1f), onStart)
                } else {
                    BlockButton("下一阶段", true, Modifier.weight(1f), onNext)
                    BlockButton("停止", false, Modifier.weight(1f), onStop)
                }
            }
        }
    }
}

/** 圆形表盘本体：按阶段时长切扇区。 */
@Composable
private fun SequenceDial(
    steps: List<Step>,
    currentIndex: Int,
    progress: Float,
    activeColor: Color,
    awaiting: Boolean,
) {
    val trackColor = ExtColors.Track
    val doneColor = ExtColors.TextDim.copy(alpha = 0.35f)
    val idleColor = ExtColors.PrimaryDim.copy(alpha = 0.55f)
    val dividerColor = ExtColors.OutlineStrong

    Canvas(modifier = Modifier.fillMaxSize()) {
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

@Composable
private fun BlockButton(
    text: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (primary) ExtColors.Primary else ExtColors.Surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (primary) Color(0xFF001639) else ExtColors.TextPrimary,
        )
    }
}

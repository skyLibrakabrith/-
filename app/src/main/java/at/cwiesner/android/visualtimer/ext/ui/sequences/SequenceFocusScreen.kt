package at.cwiesner.android.visualtimer.ext.ui.sequences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.cwiesner.android.visualtimer.ext.data.Step
import at.cwiesner.android.visualtimer.ext.data.StepSequence
import at.cwiesner.android.visualtimer.ext.domain.DeadlineCalculator
import at.cwiesner.android.visualtimer.ext.domain.StepSequenceEngine
import at.cwiesner.android.visualtimer.ext.ui.ExtColors

/**
 * 序列的全屏视图 —— **大圆盘只出现在这里**。
 *
 * 网格里序列是普通小卡片（`SequenceCell`），点进来才展开成
 * 首页时钟量级的大盘面，看阶段划分和当前进度。
 *
 * 布局与首页 24 小时时钟同构：上方大圆盘，下方一行操作。
 */
@Composable
fun SequenceFocusScreen(
    sequence: StepSequence,
    steps: List<Step>,
    phase: StepSequenceEngine.Phase,
    isActive: Boolean,
    onStart: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
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
    val activeColor = if (awaiting) ExtColors.Red else ExtColors.Primary

    val centerText = when (phase) {
        is StepSequenceEngine.Phase.Running ->
            DeadlineCalculator.formatRemaining(phase.remainingMillis)
        is StepSequenceEngine.Phase.AwaitingConfirm ->
            "+" + DeadlineCalculator.formatRemaining(phase.elapsedMillis)
        is StepSequenceEngine.Phase.Finished -> "完成"
        else -> DeadlineCalculator.formatRemaining(steps.sumOf { it.durationMillis })
    }

    val landscape = at.cwiesner.android.visualtimer.ext.ui.isLandscape()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 顶部：名称 + 关闭
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sequence.name,
                    fontSize = 16.sp,
                    color = ExtColors.TextPrimary,
                )
                Text(
                    text = if (currentIndex >= 0) {
                        "第 ${currentIndex + 1}/${steps.size} 阶段"
                    } else {
                        "${steps.size} 阶段 · 共 " +
                            DeadlineCalculator.formatRemaining(
                                steps.sumOf { it.durationMillis },
                            )
                    },
                    fontSize = 12.sp,
                    color = if (isActive) activeColor else ExtColors.TextDim,
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = ExtColors.TextSecondary,
                )
            }
        }

        // 大圆盘
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp, max = if (landscape) 260.dp else 340.dp),
            contentAlignment = Alignment.Center,
        ) {
            SequenceDial(
                steps = steps,
                currentIndex = currentIndex,
                progress = progress,
                activeColor = activeColor,
                awaiting = awaiting,
                modifier = if (landscape) Modifier.fillMaxWidth(0.62f) else Modifier.fillMaxSize(),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = centerText,
                    fontSize = 52.sp,
                    color = ExtColors.TextPrimary,
                )
                steps.getOrNull(currentIndex)?.label?.let {
                    Text(it, fontSize = 13.sp, color = ExtColors.TextSecondary)
                }
            }
        }

        // 下一阶段预告
        steps.getOrNull(currentIndex + 1)?.let { next ->
            Text(
                text = "下一阶段：" + (next.label ?: "未命名") +
                    " · " + DeadlineCalculator.formatRemaining(next.durationMillis),
                fontSize = 12.sp,
                color = ExtColors.TextDim,
            )
        }

        // 操作
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!isActive) {
                TextButton(onClick = onStart, modifier = Modifier.weight(1f)) { Text("开始") }
            } else {
                TextButton(onClick = onNext, modifier = Modifier.weight(1f)) { Text("下一阶段") }
                TextButton(onClick = onStop, modifier = Modifier.weight(1f)) { Text("停止") }
            }
            TextButton(onClick = onReset, modifier = Modifier.weight(1f)) { Text("重置") }
        }
    }
}

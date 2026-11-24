package at.cwiesner.android.visualtimer.ext.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.cwiesner.android.visualtimer.ext.data.TimerItem
import at.cwiesner.android.visualtimer.ext.domain.DeadlineCalculator
import at.cwiesner.android.visualtimer.ext.domain.TimerRuntime
import at.cwiesner.android.visualtimer.ext.settings.ColorMode
import at.cwiesner.android.visualtimer.ext.ui.ExtColors
import at.cwiesner.android.visualtimer.ext.ui.wheel.PieTimerView

/** 时刻显示 HH:mm。 */
private fun clockOf(epochMillis: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
    return "%02d:%02d".format(
        cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE),
    )
}

/**
 * 单计时器整页视图。
 *
 * 交互分区明确：
 * - **盘面外圈**：拖拽调整时长（仅未运行时允许），步进 30 秒
 * - **盘面中央的圆**：点一下开始 / 暂停 / 超时后重置
 * - **底部**：重置 + 开始/暂停 按钮
 *
 * 「改时长」和「启停」分开在两个热区，避免想暂停却改成了时长。
 */
@Composable
fun TimerFocusScreen(
    timer: TimerItem,
    runtime: TimerRuntime?,
    colorMode: ColorMode,
    intervalMinutes: Long,
    fractionDivisions: Int,
    thresholdMinutes: List<Long>,
    thresholdColors: List<Color>,
    onDurationCommit: (Long) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = runtime?.running == true
    val overdue = (runtime?.overdueMillis ?: 0L) > 0L
    var draft by remember(timer.id) { mutableLongStateOf(timer.durationMillis) }

    val remain = if (running) (runtime?.remainingMillis ?: draft) else draft
    val total = if (running) (runtime?.totalMillis ?: draft) else draft
    val landscape = at.cwiesner.android.visualtimer.ext.ui.isLandscape()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = timer.label ?: "未命名",
                fontSize = 16.sp,
                color = ExtColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = ExtColors.TextSecondary,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth(if (landscape) 0.62f else 1f)
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            // 外圈：拖拽改时长（未运行时）
            PieTimerView(
                totalMillis = total,
                remainingMillis = remain,
                running = running,
                onDurationChange = if (running) null else { d -> draft = d },
                onDurationCommit = if (running) null else { d ->
                    draft = d
                    onDurationCommit(d)
                },
                modifier = Modifier.fillMaxWidth(),
                maxMinutes = 60,
                stepMinutes = 0.5f,
                centerFontSize = 56f,
                showCenterText = false,
                colorMode = colorMode,
                intervalMinutes = intervalMinutes,
                fractionDivisions = fractionDivisions,
                thresholdMinutes = thresholdMinutes,
                thresholdColors = thresholdColors,
                overdueMillis = runtime?.overdueMillis ?: 0L,
            )

            // 内侧圆：启停热区
            InnerControlCircle(
                overdue = overdue,
                remainText = if (overdue) {
                    "+" + DeadlineCalculator.formatRemaining(runtime?.overdueMillis ?: 0L)
                } else {
                    DeadlineCalculator.formatRemaining(remain)
                },
                hint = when {
                    overdue -> "点击重置"
                    running -> "点击暂停"
                    else -> "点击开始"
                },
                onClick = {
                    when {
                        overdue -> onReset()
                        running -> onPause()
                        else -> onStart()
                    }
                },
            )
        }

        Text(
            text = when {
                overdue -> "已超时 " + DeadlineCalculator.formatRemaining(runtime?.overdueMillis ?: 0L)
                running -> "剩余 " + DeadlineCalculator.formatRemaining(remain) +
                    " · " + clockOf(System.currentTimeMillis() + remain) + " 结束"
                else -> "时长 " + DeadlineCalculator.formatRemaining(draft) +
                    " · " + clockOf(System.currentTimeMillis() + draft) + " 结束"
            },
            fontSize = 13.sp,
            color = if (overdue) ExtColors.Red else ExtColors.TextDim,
        )

        Text(
            text = if (running) "计时中不可调整时长" else "拖动外圈调整时长 · 步进 30 秒",
            fontSize = 11.sp,
            color = ExtColors.TextDim,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PillButton(
                text = "重置",
                primary = false,
                modifier = Modifier.weight(1f),
                onClick = onReset,
            )
            PillButton(
                text = if (running) "暂停" else "开始",
                primary = true,
                modifier = Modifier.weight(1f),
                onClick = { if (running) onPause() else onStart() },
            )
        }
    }
}

/**
 * 盘面中央的可点击圆 —— 启停 / 超时重置的独立热区。
 *
 * 与外圈拖拽层分开，手势不冲突。
 */
@Composable
private fun InnerControlCircle(
    overdue: Boolean,
    remainText: String,
    hint: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (overdue) ExtColors.Red.copy(alpha = 0.18f)
                else ExtColors.PureBlack.copy(alpha = 0.55f),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 34.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = remainText,
            fontSize = 42.sp,
            fontWeight = FontWeight.Light,
            color = if (overdue) ExtColors.Red else ExtColors.TextPrimary,
        )
        Text(
            text = hint,
            fontSize = 11.sp,
            color = if (overdue) ExtColors.Red else ExtColors.TextDim,
        )
    }
}

@Composable
private fun PillButton(
    text: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (primary) ExtColors.Primary else ExtColors.Surface)
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (primary) Color(0xFF001639) else ExtColors.TextPrimary,
        )
    }
}

package at.cwiesner.android.visualtimer.ext.ui.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
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
 * 单个表盘的整页视图 —— 与首页 24 小时时钟同构的全屏形态。
 *
 * 只有一个大圆盘和底部一行操作，看单个计时器时视线不被别的内容干扰。
 * 未运行时可以直接在盘面上拖动改时长（[onDurationChange]）。
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
    onDurationChange: (Long) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = runtime?.running == true
    val remain = if (running) (runtime?.remainingMillis ?: timer.durationMillis) else timer.durationMillis
    val total = if (running) (runtime?.totalMillis ?: timer.durationMillis) else timer.durationMillis

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = timer.label ?: "未命名",
                fontSize = 15.sp,
                color = ExtColors.TextSecondary,
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

        PieTimerView(
            totalMillis = total,
            remainingMillis = remain,
            running = running,
            onDurationChange = if (running) null else onDurationChange,
            modifier = Modifier.fillMaxWidth(),
            maxMinutes = 60,
            stepMinutes = 1,
            centerFontSize = 56f,
            centerLabel = if (running) null else "拖动盘面调整时长",
            colorMode = colorMode,
            intervalMinutes = intervalMinutes,
            fractionDivisions = fractionDivisions,
            thresholdMinutes = thresholdMinutes,
            thresholdColors = thresholdColors,
        )

        Text(
            text = if (running) {
                "剩余 " + DeadlineCalculator.formatRemaining(remain) +
                    " · " + clockOf(System.currentTimeMillis() + remain) + " 结束"
            } else {
                "时长 " + DeadlineCalculator.formatRemaining(timer.durationMillis) +
                    " · " + clockOf(System.currentTimeMillis() + timer.durationMillis) + " 结束"
            },
            fontSize = 13.sp,
            color = ExtColors.TextDim,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (running) {
                TextButton(onClick = onPause, modifier = Modifier.weight(1f)) { Text("暂停") }
            } else {
                TextButton(onClick = onStart, modifier = Modifier.weight(1f)) { Text("开始") }
            }
            TextButton(onClick = onReset, modifier = Modifier.weight(1f)) { Text("重置") }
        }
    }
}
